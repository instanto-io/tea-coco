/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

/** A draft timesheet returned by an application boundary. */
public record Timesheet(String employeeId, int hours) {}
