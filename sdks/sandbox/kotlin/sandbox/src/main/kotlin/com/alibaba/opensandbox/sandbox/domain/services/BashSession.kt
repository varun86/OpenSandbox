/*
 * Copyright 2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.opensandbox.sandbox.domain.services

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunInSessionRequest

/**
 * Bash session service for sandbox environments.
 *
 * Provides create_session, run_in_session, and delete_session to manage
 * stateful bash sessions with persistent shell state across multiple runs.
 *
 * This interface is internal. It is not the recommended runtime entry point.
 * Use [Commands.createSession], [Commands.runInSession], and [Commands.deleteSession]
 * via `sandbox.commands()` instead.
 */
internal interface BashSession {
    /**
     * Creates a new bash session.
     *
     * @param cwd Optional working directory for the session
     * @return Session ID for use with runInSession and deleteSession
     */
    fun createSession(cwd: String? = null): String

    /**
     * Runs shell code in an existing bash session and streams output via SSE.
     *
     * @param sessionId Session ID from createSession
     * @param request Code to execute and optional cwd/timeout/handlers
     * @return Execution result with stdout/stderr and completion status
     */
    fun runInSession(sessionId: String, request: RunInSessionRequest): Execution

    /**
     * Deletes a bash session and releases resources.
     *
     * @param sessionId Session ID to delete
     */
    fun deleteSession(sessionId: String)
}
