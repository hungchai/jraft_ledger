package com.tomma8.ledger.projection;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tomma8.ledger.dao.mapper")
public class ProjectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectionApplication.class, args);
    }
}
