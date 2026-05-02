package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuicHolePunchCoordinator {
    private static final Pattern PUNCH_ENDPOINT_PATTERN = Pattern.compile("\"punch_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_STATUS_PATTERN = Pattern.compile("\"punch_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_TOKEN_PATTERN = Pattern.compile("\"punch_token\"\\s*:\\s*\"([^\"]*)\"");

    private QuicHolePunchCoordinator() {
    }

    static void prepareOneShotPunch(String roomBody, String roomName, RelayEndpoint quicEndpoint, String attemptId, String clientKey) {
        Optional<String> punchStatusValue = findFirst(PUNCH_STATUS_PATTERN, roomBody).map(String::trim);
        if (punchStatusValue.isEmpty() || !shouldSendPunch(punchStatusValue.get())) {
            return;
        }

        Optional<String> punchEndpointValue = findFirst(PUNCH_ENDPOINT_PATTERN, roomBody)
                .or(() -> Optional.of(quicEndpoint.host() + ":" + quicEndpoint.port()));
        Optional<RelayEndpoint> punchEndpoint = punchEndpointValue.flatMap(value -> RelayEndpoint.parse(value.trim(), "punch_endpoint"));
        if (punchEndpoint.isEmpty()) {
            return;
        }

        String punchToken = findFirst(PUNCH_TOKEN_PATTERN, roomBody).orElse("");
        boolean punched = UdpHolePunchClient.oneShotPunch(punchEndpoint.get(), roomName, punchToken, 3, 120);
        if (!punched) {
            return;
        }

        QuicAttemptRecorder.markPunchSent(roomName, clientKey, attemptId);
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] One-shot UDP hole punch sent. room_name='{}', attempt_id='{}', endpoint='{}:{}'",
                roomName,
                attemptId,
                punchEndpoint.get().host(),
                punchEndpoint.get().port()
        );
    }

    private static boolean shouldSendPunch(String punchStatus) {
        String status = punchStatus == null ? "" : punchStatus.trim().toLowerCase();
        return "ready".equals(status)
                || "probing".equals(status)
                || "client_probe_sent".equals(status);
    }

    private static Optional<String> findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1));
    }
}

