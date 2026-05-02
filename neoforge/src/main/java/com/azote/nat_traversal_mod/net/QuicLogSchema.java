package com.azote.nat_traversal_mod.net;

final class QuicLogSchema {
	static final String FIELD_PHASE = "phase";
	static final String FIELD_ROOM_NAME = "room_name";
	static final String FIELD_ATTEMPT_ID = "attempt_id";
	static final String FIELD_TARGET = "target";
	static final String FIELD_ERROR_CODE = "error_code";

	static final String PHASE_DISABLED = "disabled";
	static final String PHASE_STARTED = "started";
	static final String PHASE_START_FAILED = "start_failed";
	static final String PHASE_ESTABLISHED = "established";
	static final String PHASE_DIAL_START = "dial_start";
	static final String PHASE_CHANNEL_CONNECT_FAILED = "channel_connect_failed";
	static final String PHASE_STREAM_CREATE_FAILED = "stream_create_failed";
	static final String PHASE_STREAM_READY = "stream_ready";
	static final String PHASE_PRE_PUNCH_SENT = "pre_punch_sent";

	private QuicLogSchema() {
	}
}

