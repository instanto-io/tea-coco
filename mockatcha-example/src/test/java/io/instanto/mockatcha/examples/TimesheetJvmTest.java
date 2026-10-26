/* Copyright 2026 Instanto contributors. SPDX-License-Identifier: Apache-2.0 */
package io.instanto.mockatcha.examples;

import static io.instanto.mockatcha.Mockatcha.*;
import static org.junit.Assert.*;

import io.instanto.mockatcha.Mockatcha;
import org.junit.After;
import org.junit.Test;

/** The missing-draft branch is deliberately exercised only on the JVM. */
public class TimesheetJvmTest {
  @After
  public void releaseMocks() {
    Mockatcha.releaseMocks();
  }

  @Test
  public void missingDraftDoesNotSubmitOrRequestApproval() {
    TimesheetRepository repository = mock(TimesheetRepository.class);
    ApprovalGateway approvals = mock(ApprovalGateway.class);
    when(repository.findDraft("A-17")).thenReturn(null);
    var service = new TimesheetService(repository, approvals);

    assertEquals(
        "No draft timesheet for A-17",
        assertThrows(IllegalStateException.class, () -> service.submit("A-17")).getMessage());
    verify(repository, never()).markSubmitted("A-17");
    verify(approvals, never()).requestReview("A-17", 0);
  }
}
