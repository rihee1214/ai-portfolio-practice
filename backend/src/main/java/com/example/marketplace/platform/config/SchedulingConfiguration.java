package com.example.marketplace.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** DB-backed schedules remain active independently of the media owner's role. */
@Configuration(proxyBeanMethods = false)
@Profile("db")
@EnableScheduling
public class SchedulingConfiguration { }
