package com.fancy.hotel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * hotel-booking 启动类
 *
 * <p>{@code @EnableScheduling} 是 P2 加的：订单超时处理有两个定时任务
 * （延迟队列消费 + 兜底扫表），见 {@code job.OrderTimeoutJob}。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.fancy.hotel.mapper")
public class HotelBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelBookingApplication.class, args);
    }
}
