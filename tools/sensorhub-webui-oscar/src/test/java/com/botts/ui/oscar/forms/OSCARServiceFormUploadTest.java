package com.botts.ui.oscar.forms;

import com.vaadin.v7.data.util.ObjectProperty;
import com.vaadin.v7.ui.TextField;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.eml.EmlRuntimeStatus;

import java.time.Instant;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OSCARServiceFormUploadTest {

    @Test
    public void recordsUploadedSpreadsheetKeyInBoundConfigurationField() {
        ObjectProperty<String> configuredPath = new ObjectProperty<>("");
        TextField field = new TextField();
        field.setPropertyDataSource(configuredPath);
        field.setBuffered(false);
        field.setReadOnly(true);

        OSCARServiceForm.recordUploadedObjectKey(field, "canonical-50-lanes.csv");

        assertEquals("canonical-50-lanes.csv", configuredPath.getValue());
    }

    @Test
    public void formatsLocalizedEmlRuntimeStatusSummaries() {
        EmlRuntimeStatus unloaded = EmlRuntimeStatus.notLoaded("");
        assertEquals("Not loaded", OSCARServiceForm.formatEmlStatusText(Locale.ENGLISH, unloaded));
        assertEquals("No cargado", OSCARServiceForm.formatEmlStatusText(new Locale("es"), unloaded));
        assertEquals("Non chargé", OSCARServiceForm.formatEmlStatusText(Locale.FRENCH, unloaded));
        assertEquals("Δεν έχει φορτωθεί", OSCARServiceForm.formatEmlStatusText(new Locale("el"), unloaded));

        EmlRuntimeStatus loaded = new EmlRuntimeStatus(
                true,
                "eml-vm250-1.0.2.jar",
                "abc123",
                "1.0.2",
                "multi_site_collimated",
                "multi_site_uncollimated",
                Instant.parse("2026-11-05T12:00:00Z"),
                "");
        assertEquals("Active: eml-vm250-1.0.2.jar (v1.0.2, multi_site_collimated)",
                OSCARServiceForm.formatEmlStatusText(Locale.ENGLISH, loaded));
        assertTrue(OSCARServiceForm.formatEmlStatusText(new Locale("el"), loaded)
                .startsWith("Ενεργό: eml-vm250-1.0.2.jar"));
    }
}
