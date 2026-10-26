/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

/** Real application code tested with mocked storage and approval boundaries. */
public final class TimesheetService {

  private final TimesheetRepository timesheets;
  private final ApprovalGateway approvals;

  public TimesheetService(TimesheetRepository timesheets, ApprovalGateway approvals) {
    this.timesheets = timesheets;
    this.approvals = approvals;
  }

  public Submission submit(String employeeId) {
    Timesheet timesheet = timesheets.findDraft(employeeId);
    if (timesheet == null) {
      throw new IllegalStateException("No draft timesheet for " + employeeId);
    }

    timesheets.markSubmitted(employeeId);
    boolean reviewRequired = timesheet.hours() > 40;
    if (reviewRequired) {
      approvals.requestReview(employeeId, timesheet.hours());
    }
    return new Submission(timesheet.hours(), reviewRequired);
  }
}
