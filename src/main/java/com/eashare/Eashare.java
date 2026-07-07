package com.eashare;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)

@MapperScan("com.eashare.mapper")
@SpringBootApplication
public class Eashare {

    public static void main(String[] args) {
        SpringApplication.run(Eashare.class, args);
    }

}
