/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.mockatcha.examples;

/** External approval boundary used only for unusually long timesheets. */
public interface ApprovalGateway {

  void requestReview(String employeeId, int hours);
}
