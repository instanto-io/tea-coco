/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

/** Result returned to the UI after a timesheet is submitted. */
public final class Submission {

  private final int hours;
  private final boolean reviewRequired;

  Submission(int hours, boolean reviewRequired) {
    this.hours = hours;
    this.reviewRequired = reviewRequired;
  }

  public int hours() {
    return hours;
  }

  public boolean reviewRequired() {
    return reviewRequired;
  }
}
