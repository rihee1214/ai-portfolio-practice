package com.example.marketplace.media.infrastructure;

import com.example.marketplace.media.api.MediaException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class ImageNormalizer {
    public static final int MAX_BYTES=5*1024*1024;
    private final java.util.concurrent.Semaphore permits=new java.util.concurrent.Semaphore(2);
    public record Image(byte[] png,int width,int height) { }
    public Image normalize(String filename,byte[] bytes) {
        if(filename==null||bytes==null||bytes.length==0||bytes.length>MAX_BYTES) { throw new MediaException(400); }
        if(!permits.tryAcquire()) { throw new MediaException(429); }
        try(var in=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            String format=format(bytes);
            String extension=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
            if(!(extension.equals(format)||format.equals("jpeg")&&extension.equals("jpg"))) { throw new MediaException(400); }
            var readers=ImageIO.getImageReaders(in);
            if(!readers.hasNext()) { throw new MediaException(400); }
            var reader=readers.next();
            try {
                var warned=new java.util.concurrent.atomic.AtomicBoolean(); reader.addIIOReadWarningListener((source,message)->warned.set(true));
                reader.setInput(in,false,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||width>4096||height>4096||(long)width*height>16000000) { throw new MediaException(400); }
                BufferedImage decoded=reader.read(0);
                if(decoded==null||warned.get()) { throw new MediaException(400); }
                var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                var graphics=clean.createGraphics();
                try { graphics.drawImage(decoded,0,0,null); } finally { graphics.dispose(); decoded.flush(); }
                var bytesOut=new ByteArrayOutputStream() {
                    @Override public synchronized void write(byte[] b,int off,int len) { if((long)count+len>MAX_BYTES) { throw new MediaException(400); } super.write(b,off,len); }
                    @Override public synchronized void write(int b) { if(count>=MAX_BYTES) { throw new MediaException(400); } super.write(b); }
                };
                try(var out=new MemoryCacheImageOutputStream(bytesOut)) { ImageIO.write(clean,"PNG",out); out.flush(); }
                finally { clean.flush(); }
                return new Image(bytesOut.toByteArray(),width,height);
            } finally { reader.dispose(); }
        } catch(MediaException rejected) { throw rejected; }
        catch(java.io.IOException|RuntimeException invalid) { throw new MediaException(400); }
        finally { permits.release(); }
    }
    private String format(byte[] bytes) {
        if(bytes.length>=12&&bytes[0]==(byte)0x89&&new String(bytes,1,3,StandardCharsets.US_ASCII).equals("PNG")) {
            int offset=8; boolean ended=false;
            while(offset+12<=bytes.length) {
                long size=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,offset,4).getInt());
                if(size>bytes.length-offset-12) { throw new MediaException(400); }
                var crc=new java.util.zip.CRC32(); crc.update(bytes,offset+4,(int)size+4);
                if(crc.getValue()!=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,offset+8+(int)size,4).getInt())) { throw new MediaException(400); }
                String chunk=new String(bytes,offset+4,4,StandardCharsets.US_ASCII);
                if(chunk.equals("acTL")) { throw new MediaException(400); }
                offset+=(int)size+12;
                if(chunk.equals("IEND")) { ended=true; break; }
            }
            if(!ended) { throw new MediaException(400); }
            return "png";
        }
        if(bytes.length>=4&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8) {
            boolean end=false; for(int i=2;i<bytes.length;i++) { if(bytes[i-1]==(byte)0xff&&bytes[i]==(byte)0xd9) { end=true; break; } }
            if(!end) { throw new MediaException(400); } return "jpeg";
        }
        if(bytes.length>=20&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP")) {
            long length=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt())+8;
            if(length!=bytes.length) { throw new MediaException(400); }
            int offset=12;
            while(offset+8<=bytes.length) {
                String chunk=new String(bytes,offset,4,StandardCharsets.US_ASCII);
                long size=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,offset+4,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                if(size>bytes.length-offset-8||chunk.equals("ANIM")||chunk.equals("ANMF")||chunk.equals("VP8X")&&size>0&&(bytes[offset+8]&2)!=0) { throw new MediaException(400); }
                offset+=(int)size+8+(int)(size%2);
            }
            if(offset!=bytes.length) { throw new MediaException(400); } return "webp";
        }
        throw new MediaException(400);
    }
}
