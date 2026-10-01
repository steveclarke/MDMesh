package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public class CallNetworkPolicyTest {
    @Test
    public void null_true_and_false_survive_json_copy_and_desired_config() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Boolean value : Arrays.asList(null, Boolean.TRUE, Boolean.FALSE)) {
            Configuration c = new Configuration();
            c.setOutgoingCalls(value); c.setMobileNetworksConfig(value);
            Configuration copy = c.newCopy();
            assertEquals(value, copy.getOutgoingCalls());
            assertEquals(value, copy.getMobileNetworksConfig());
            Configuration back = mapper.readValue(mapper.writeValueAsString(c), Configuration.class);
            assertEquals(value, back.getOutgoingCalls());
            assertEquals(value, back.getMobileNetworksConfig());
            DesiredConfig d = DesiredConfigBuilder.build(back, Collections.emptyList());
            for (String key : Arrays.asList("outgoingCalls", "mobileNetworksConfig")) {
                assertEquals(value != null, d.getPolicies().containsKey(key));
                assertEquals(value, d.getPolicies().get(key));
            }
        }
    }

    @Test
    public void old_agents_do_not_receive_new_keys_and_allowed_calls_do_not_clear_network_lock() {
        Configuration c = new Configuration(); c.setOutgoingCalls(true); c.setMobileNetworksConfig(false);
        DesiredConfig full = DesiredConfigBuilder.build(c, Collections.emptyList());
        DesiredConfig old = DesiredConfigBuilder.forCapabilities(full, Collections.singleton("device.configApply"));
        assertFalse(old.getPolicies().containsKey("outgoingCalls"));
        assertFalse(old.getPolicies().containsKey("mobileNetworksConfig"));
        assertEquals(DesiredConfigBuilder.build(new Configuration(), Collections.emptyList()).getRevision(), old.getRevision());
        DesiredConfig current = DesiredConfigBuilder.forCapabilities(full,
                new HashSet<>(Arrays.asList("policy.outgoingCalls", "policy.mobileNetworksConfig")));
        assertEquals(Boolean.TRUE, current.getPolicies().get("outgoingCalls"));
        assertEquals(Boolean.FALSE, current.getPolicies().get("mobileNetworksConfig"));
        assertEquals(full.getRevision(), current.getRevision());
        assertEquals(2, full.getPolicies().size()); // filtering one device must not mutate a shared document
        DesiredConfig callsOnly = DesiredConfigBuilder.forCapabilities(full, Collections.singleton("policy.outgoingCalls"));
        assertEquals(Boolean.TRUE, callsOnly.getPolicies().get("outgoingCalls"));
        assertFalse(callsOnly.getPolicies().containsKey("mobileNetworksConfig"));
    }

    @Test
    public void effective_state_is_optional_and_explicit_false_is_preserved() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentDeviceState old = mapper.readValue("{\"battery\":50}", AgentDeviceState.class);
        assertNull(old.getOutgoingCallsAllowed()); assertNull(old.getMobileNetworksConfigAllowed());
        AgentDeviceState state = mapper.readValue("{\"outgoingCallsAllowed\":true,\"mobileNetworksConfigAllowed\":false}", AgentDeviceState.class);
        assertEquals(Boolean.TRUE, state.getOutgoingCallsAllowed());
        assertEquals(Boolean.FALSE, state.getMobileNetworksConfigAllowed());
        assertTrue(mapper.writeValueAsString(state).contains("\"mobileNetworksConfigAllowed\":false"));
    }
}
