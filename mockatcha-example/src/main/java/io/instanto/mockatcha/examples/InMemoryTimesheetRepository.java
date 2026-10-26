/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A working repository, used in the examples as something worth keeping rather than replacing.
 *
 * <p>A test that spies on this keeps the real lookup and submission behaviour and replaces only the
 * part it needs to control.
 */
public class InMemoryTimesheetRepository implements TimesheetRepository {

  private final Map<String, Timesheet> drafts = new HashMap<>();
  private final List<String> submitted = new ArrayList<>();

  public InMemoryTimesheetRepository() {
    // Empty repository initially
  }

  public void addDraft(Timesheet timesheet) {
    drafts.put(timesheet.employeeId(), timesheet);
  }

  @Override
  public Timesheet findDraft(String employeeId) {
    return drafts.get(employeeId);
  }

  @Override
  public void markSubmitted(String employeeId) {
    submitted.add(employeeId);
  }

  public List<String> submitted() {
    return Collections.unmodifiableList(submitted);
  }
}
