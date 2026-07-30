package com.example.myagent.skillreview;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillReviewControllerTest {

  private static final CurrentUser ALICE = new CurrentUser(1L, "alice", "USER");

  @Test
  void approveUsesAuthenticatedUserAsReviewer() {
    SkillReviewService service = mock(SkillReviewService.class);
    SkillReviewController controller = new SkillReviewController(service);
    ApproveSkillReviewRequest request = new ApproveSkillReviewRequest(List.of("prod"));
    SkillReviewDto response =
        new SkillReviewDto("my-skill", null, "APPROVED", null, null, List.of("prod"), 0, 0, 0);
    when(service.approve(eq("my-skill"), eq(request), eq("1"), eq("alice")))
        .thenReturn(response);

    controller.approve(ALICE, "my-skill", request).block();

    verify(service).approve("my-skill", request, "1", "alice");
  }

  @Test
  void rejectUsesAuthenticatedUserAsReviewer() {
    SkillReviewService service = mock(SkillReviewService.class);
    SkillReviewController controller = new SkillReviewController(service);
    RejectSkillReviewRequest request = new RejectSkillReviewRequest("risk");
    SkillReviewDto response =
        new SkillReviewDto("my-skill", null, "REJECTED", null, null, List.of(), 0, 0, 0);
    when(service.reject(eq("my-skill"), eq(request), eq("1"), eq("alice")))
        .thenReturn(response);

    controller.reject(ALICE, "my-skill", request).block();

    verify(service).reject("my-skill", request, "1", "alice");
  }
}
