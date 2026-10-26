/* Copyright 2026 Instanto contributors. SPDX-License-Identifier: Apache-2.0 */
package io.instanto.mockatcha.examples;

import static io.instanto.mockatcha.ArgumentMatchers.*;
import static io.instanto.mockatcha.Mockatcha.*;
import static org.junit.Assert.*;

import io.instanto.mockatcha.Mockatcha;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.SkipJVM;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
@SkipJVM
public class TimesheetCoreBrowserIT {
  @After
  public void releaseMocks() {
    Mockatcha.releaseMocks();
  }

  @Test
  public void ordinaryWeekIsSubmittedWithoutApproval() {
    TimesheetRepository repository = mock(TimesheetRepository.class);
    ApprovalGateway approvals = mock(ApprovalGateway.class);
    when(repository.findDraft("A-17")).thenReturn(new Timesheet("A-17", 38));

    Submission result = new TimesheetService(repository, approvals).submit("A-17");

    assertEquals(38, result.hours());
    assertFalse(result.reviewRequired());
    verify(repository).markSubmitted("A-17");
    verify(approvals, never()).requestReview(anyString(), anyInt());
  }

  @Test
  public void fortyHoursIsStillAnOrdinaryWeek() {
    TimesheetRepository repository = mock(TimesheetRepository.class);
    ApprovalGateway approvals = mock(ApprovalGateway.class);
    when(repository.findDraft("B-24")).thenReturn(new Timesheet("B-24", 40));

    Submission result = new TimesheetService(repository, approvals).submit("B-24");

    assertEquals(40, result.hours());
    assertFalse(result.reviewRequired());
    verify(approvals, never()).requestReview(anyString(), anyInt());
  }
}
