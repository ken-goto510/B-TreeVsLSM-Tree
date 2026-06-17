package com.example.benchmark.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ALB / ECS ヘルスチェック専用エンドポイント。
 * DB の接続状態に依存せず即座に 200 OK を返す。
 * ALB ターゲットグループのヘルスチェックパスをこの /health に向けること。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
