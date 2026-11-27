package com.hisabkhata.data.cloud

/**
 * Instrumentation helper: a signed-out session with no network client behind it.
 *
 * Device tests use it so repositories behave exactly as on a fresh install before
 * sign-in: writes save locally and queue nothing, and nothing touches the cloud.
 */
class TestCloudSession : CloudSession()
