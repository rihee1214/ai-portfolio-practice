package com.example.marketplace.media.infrastructure;

import com.example.marketplace.media.api.MediaException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageNormalizerTest {
    private final ImageNormalizer codec=new ImageNormalizer();
    private byte[] fixture(String name) throws Exception { try(var in=getClass().getResourceAsStream("/media/"+name)) { return in.readAllBytes(); } }
    private byte[] image(String format,int w,int h) throws Exception {
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); image.setRGB(0,0,0xffdd3344);
        var out=new ByteArrayOutputStream(); ImageIO.write(image,format,out); return out.toByteArray();
    }
    @Test void jpegAndPngAreDecodedAndReencodedWithoutTrailingPayload() throws Exception {
        for(String format:java.util.List.of("png","jpeg")) {
            var bytes=image(format,4,3); var extra=new ByteArrayOutputStream(); extra.write(bytes); extra.write("<script>payload</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var clean=codec.normalize("../untrusted."+format,extra.toByteArray());
            assertEquals(4,clean.width()); assertEquals(3,clean.height());
            assertEquals(0x89,Byte.toUnsignedInt(clean.png()[0]));
            assertFalse(new String(clean.png(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("script"));
            assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(clean.png())));
        }
    }
    @Test void lossyAndAlphaWebpDecodeAndPreserveAlpha() throws Exception {
        for(String name:java.util.List.of("lossy.webp","alpha.webp")) {
            var normalized=codec.normalize(name,fixture(name)); assertEquals(4,normalized.width()); assertEquals(3,normalized.height());
            if(name.startsWith("alpha")) { assertEquals(128,ImageIO.read(new java.io.ByteArrayInputStream(normalized.png())).getRGB(0,0)>>>24); }
        }
    }
    @Test void rejectsAnimationSpoofedExtensionsAndBrokenImages() throws Exception {
        assertThrows(MediaException.class,()->codec.normalize("anim.webp",fixture("animated.webp")));
        assertThrows(MediaException.class,()->codec.normalize("x.jpg",image("png",2,2)));
        assertThrows(MediaException.class,()->codec.normalize("x.svg","<svg/>".getBytes()));
        assertThrows(MediaException.class,()->codec.normalize("x.png",new byte[]{1,2,3}));
        byte[] png=image("png",4,3);
        assertThrows(MediaException.class,()->codec.normalize("x.png",java.util.Arrays.copyOf(png,png.length/2)));
        byte[] badCrc=png.clone(); badCrc[29]^=1;
        assertThrows(MediaException.class,()->codec.normalize("bad-crc.png",badCrc));
    }
    @Test void rejectsByteAndPixelLimitsBeforeFullDecode() throws Exception {
        assertThrows(MediaException.class,()->codec.normalize("x.png",new byte[5*1024*1024+1]));
        assertThrows(MediaException.class,()->codec.normalize("x.png",image("png",4097,1)));
        byte[] png=image("png",2,2); java.nio.ByteBuffer.wrap(png,16,8).putInt(4096).putInt(4096);
        var crc=new java.util.zip.CRC32(); crc.update(png,12,17); java.nio.ByteBuffer.wrap(png,29,4).putInt((int)crc.getValue());
        assertThrows(MediaException.class,()->codec.normalize("x.png",png));
    }
    @Test void rejectsApngDamagedJpegAndOversizedReencodedOutput() throws Exception {
        byte[] png=image("png",4,3);
        var chunk=new ByteArrayOutputStream(); var data=new java.io.DataOutputStream(chunk);
        data.writeInt(8); data.writeBytes("acTL"); data.writeInt(2); data.writeInt(0);
        var crc=new java.util.zip.CRC32(); crc.update(chunk.toByteArray(),4,12); data.writeInt((int)crc.getValue());
        var animated=new ByteArrayOutputStream(); animated.write(png,0,33); animated.write(chunk.toByteArray()); animated.write(png,33,png.length-33);
        assertThrows(MediaException.class,()->codec.normalize("animation.png",animated.toByteArray()));
        byte[] jpeg=image("jpeg",32,32); byte[] broken=java.util.Arrays.copyOf(jpeg,jpeg.length-30);
        broken[broken.length-2]=(byte)0xff; broken[broken.length-1]=(byte)0xd9;
        assertThrows(MediaException.class,()->codec.normalize("broken.jpg",broken));
        var noisy=new BufferedImage(1600,1600,BufferedImage.TYPE_INT_RGB); var random=new java.util.Random(42);
        for(int y=0;y<1600;y++) { for(int x=0;x<1600;x++) { noisy.setRGB(x,y,random.nextInt()); } }
        var input=new ByteArrayOutputStream(); ImageIO.write(noisy,"jpeg",input);
        org.junit.jupiter.api.Assertions.assertTrue(input.size()<ImageNormalizer.MAX_BYTES);
        assertThrows(MediaException.class,()->codec.normalize("large-output.jpg",input.toByteArray()));
    }
}
