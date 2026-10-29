package com.example.marketplace.payment;
import com.example.marketplace.payment.infrastructure.MockPaymentProvider;
import com.example.marketplace.payment.infrastructure.PaymentProviderConfiguration;
import com.example.marketplace.payment.api.PaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;
class PaymentProviderConfigurationTest {
 private ApplicationContextRunner application() { return new ApplicationContextRunner().withPropertyValues("spring.profiles.active=db").withUserConfiguration(PaymentProviderConfiguration.class).withBean(MockPaymentProvider.class,()->mock(MockPaymentProvider.class)).withBean(NamedParameterJdbcTemplate.class,()->mock(NamedParameterJdbcTemplate.class)); }
 @Test void missingKeysRemainUnavailableAndProductionEndpointIsFixed() {
  application().run(context->{
   assertEquals(null,context.getStartupFailure());var settings=context.getBean(PaymentProviderConfiguration.Settings.class);assertFalse(settings.ready());assertEquals("https://api.tosspayments.com",settings.endpoint());var demo=context.getBean(MockPaymentProvider.class);var provider=context.getBean(PaymentProvider.class);assertNotSame(demo,provider);assertSame(context.getBean("paymentProvider"),provider);var id=java.util.UUID.randomUUID();
   for(String mode:java.util.List.of("DEMO","SANDBOX")) { var r=new PaymentProvider.Request(id,id,1,mode);org.mockito.Mockito.when(demo.approve(r)).thenReturn(PaymentProvider.Result.SUCCEEDED);org.mockito.Mockito.when(demo.query(r)).thenReturn(PaymentProvider.Result.SUCCEEDED);org.mockito.Mockito.when(demo.cancel(r)).thenReturn(PaymentProvider.Result.SUCCEEDED);org.mockito.Mockito.when(demo.queryCancellation(r)).thenReturn(PaymentProvider.Result.SUCCEEDED);org.mockito.Mockito.clearInvocations(demo);
    var expected=mode.equals("DEMO")?PaymentProvider.Result.SUCCEEDED:PaymentProvider.Result.UNKNOWN;assertEquals(java.util.List.of(expected,expected,expected,expected),java.util.List.of(provider.approve(r),provider.query(r),provider.cancel(r),provider.queryCancellation(r)));
    if(mode.equals("DEMO")) { org.mockito.Mockito.verify(demo).approve(r);org.mockito.Mockito.verify(demo).query(r);org.mockito.Mockito.verify(demo).cancel(r);org.mockito.Mockito.verify(demo).queryCancellation(r); }else { org.mockito.Mockito.verifyNoInteractions(demo); }
   }
  });
  application().withPropertyValues("marketplace.toss.endpoint=http://127.0.0.1:9000").run(context->assertNotNull(context.getStartupFailure()));
  application().withPropertyValues("marketplace.toss.secret-key=live_sk_disabled").run(context->assertNotNull(context.getStartupFailure()));
 }
 @Test void explicitCountingProviderCanOverrideDefaultRoutingWithoutAmbiguity() {
  var sentinel=mock(PaymentProvider.class);application().withBean("countingProvider",PaymentProvider.class,()->sentinel,definition->definition.setPrimary(true)).run(context->assertSame(sentinel,assertDoesNotThrow(()->context.getBean(PaymentProvider.class))));
 }
}
