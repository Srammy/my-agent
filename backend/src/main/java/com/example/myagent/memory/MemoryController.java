package com.example.myagent.memory;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

  private final MemoryService memoryService;

  public MemoryController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @GetMapping("/summary")
  public Mono<MemorySummaryDto> getSummary(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> new MemorySummaryDto(memoryService.getSummary(currentUser)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/daily")
  public Mono<MemoryDailyListDto> listDaily(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> new MemoryDailyListDto(memoryService.listDaily(currentUser)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/daily/{date}")
  public Mono<MemoryDailyDto> getDaily(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable LocalDate date) {
    return Mono.fromCallable(() -> new MemoryDailyDto(date, memoryService.getDaily(currentUser, date)))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
