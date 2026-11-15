package com.example.marketplace.api;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.media.api.Media;
import com.example.marketplace.media.infrastructure.JdbcMedia;
import com.example.marketplace.media.infrastructure.LocalImages;
import com.example.marketplace.media.infrastructure.MediaConfiguration;
import com.example.marketplace.media.web.MediaController;
import com.example.marketplace.platform.config.SchedulingConfiguration;
import com.example.marketplace.reporting.infrastructure.DashboardRuns;
import com.example.marketplace.reporting.infrastructure.DashboardSchedule;
import com.example.marketplace.seller.api.Stores;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class RuntimeRoleSchedulingTest {
    private ApplicationContextRunner runner(boolean worker) {
        var runner = new ApplicationContextRunner().withInitializer(context -> {
            context.getEnvironment().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
            context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof JdbcMedia media) {
                        var observed = spy(media); doNothing().when(observed).cleanup(); return observed;
                    }
                    if (bean instanceof DashboardSchedule dashboard) {
                        var observed = spy(dashboard); doNothing().when(observed).refresh(); return observed;
                    }
                    return bean;
                }
            });
        })
                .withPropertyValues("spring.profiles.active=db",
                "marketplace.worker.enabled=" + worker, "marketplace.media.enabled=" + !worker)
                .withUserConfiguration(SchedulingConfiguration.class, MediaConfiguration.class, JdbcMedia.class,
                        MediaController.class, DashboardSchedule.class)
                .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(Products.class, () -> mock(Products.class)).withBean(Stores.class, () -> mock(Stores.class))
                .withBean(DashboardRuns.class, () -> mock(DashboardRuns.class));
        return worker ? runner : runner.withBean(LocalImages.class, () -> mock(LocalImages.class));
    }

    @Test void apiKeepsExclusiveMediaOwnerAndRegistersActualCleanup() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LocalImages.class).hasSingleBean(Media.class)
                    .hasSingleBean(JdbcMedia.class).hasSingleBean(MediaController.class).doesNotHaveBean(DashboardSchedule.class);
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            assertThat(tasks).anySatisfy(task -> {
                task.cancel(false);
                var media = context.getBean(JdbcMedia.class);
                clearInvocations(media);
                // Execute Spring's registered wrapper and verify its actual bean/method routing.
                assertDoesNotThrow(task.getTask().getRunnable()::run);
                verify(media).cleanup();
                assertThat(task.getTask().getLastExecutionOutcome().status()).isEqualTo(org.springframework.scheduling.config.TaskExecutionOutcome.Status.SUCCESS);
                assertThat(task.getTask().getLastExecutionOutcome().throwable()).isNull();
            });
        });
    }

    @Test void workerOmitsAllMediaOwnersAndStillRegistersActualDashboardRefresh() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(LocalImages.class).doesNotHaveBean(Media.class)
                    .doesNotHaveBean(JdbcMedia.class).doesNotHaveBean(MediaController.class).hasSingleBean(DashboardSchedule.class);
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            assertThat(tasks).anySatisfy(task -> {
                task.cancel(false);
                var dashboard = context.getBean(DashboardSchedule.class);
                clearInvocations(dashboard);
                assertDoesNotThrow(task.getTask().getRunnable()::run);
                verify(dashboard).refresh();
                assertThat(task.getTask().getLastExecutionOutcome().status()).isEqualTo(org.springframework.scheduling.config.TaskExecutionOutcome.Status.SUCCESS);
                assertThat(task.getTask().getLastExecutionOutcome().throwable()).isNull();
            });
        });
    }
}
