/* Copyright 2026 Instanto contributors. SPDX-License-Identifier: Apache-2.0 */
package io.instanto.mockatcha.examples;

import static io.instanto.mockatcha.Mockatcha.*;
import static io.instanto.mockatcha.bdd.Bdd.*;
import static io.instanto.mockatcha.bdd.BddMatchers.*;
import static org.junit.Assert.*;

import io.instanto.mockatcha.Mockatcha;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@SkipJVM
public class TimesheetBddBrowserIT {
  @After
  public void releaseMocks() {
    Mockatcha.releaseMocks();
  }

  @Test
  public void givenOvertimeWhenSubmittedThenApprovalReceivesTheHours() {
    TimesheetRepository repository = mock(TimesheetRepository.class);
    ApprovalGateway approvals = mock(ApprovalGateway.class);
    spyOn(repository, "findDraft").withArgs("A-17").andReturn(new Timesheet("A-17", 46));

    Submission result = new TimesheetService(repository, approvals).submit("A-17");

    assertTrue(result.reviewRequired());
    assertEquals(46, result.hours());
    assertEquals(1, calls(approvals, "requestReview").count());
    assertEquals(List.of("A-17", 46), calls(approvals, "requestReview").first().arguments());
    verify(repository).markSubmitted(stringContaining("A-"));
  }

  @Test
  public void spyKeepsRealStorageWhileOverridingTheDraft() {
    InMemoryTimesheetRepository real = new InMemoryTimesheetRepository();
    real.addDraft(new Timesheet("A-17", 38));
    InMemoryTimesheetRepository repository = spy(InMemoryTimesheetRepository.class, real);
    ApprovalGateway approvals = mock(ApprovalGateway.class);
    spyOn(repository, "findDraft").withArgs("A-17").andReturn(new Timesheet("A-17", 46));

    new TimesheetService(repository, approvals).submit("A-17");

    assertEquals(List.of("A-17"), real.submitted());
    assertEquals(1, calls(repository, "markSubmitted").count());
    assertEquals(List.of("A-17", 46), calls(approvals, "requestReview").mostRecent().arguments());
  }
}
