/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

/** Storage boundary used by the timesheet service. */
public interface TimesheetRepository {

  Timesheet findDraft(String employeeId);

  void markSubmitted(String employeeId);
}
