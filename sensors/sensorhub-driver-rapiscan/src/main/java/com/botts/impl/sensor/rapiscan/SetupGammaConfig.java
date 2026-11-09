package com.botts.impl.sensor.rapiscan;

import org.sensorhub.api.config.DisplayInfo;

import java.util.Objects;

public class SetupGammaConfig {

    @DisplayInfo(label = "High Background Fault", desc = "Gamma high background fault threshold (cps)")
    public int highBackgroundFault = 500;

    @DisplayInfo(label = "Low Background Fault", desc = "Gamma low background fault threshold (cps)")
    public int lowBackgroundFault = 5;

    @DisplayInfo(label = "Intervals", desc = "Number of gamma measurement intervals")
    public int intervals = 5;

    @DisplayInfo(label = "Occupancy Holdin", desc = "Time to retain the occupied state after occupancy ends")
    public int occupancyHoldin = 10;

    @DisplayInfo(label = "N Sigma", desc = "N value for threshold calculation")
    public double nsigma = 7.0;

    @DisplayInfo(label = "Detectors", desc = "Active gamma detectors mask")
    public String detectors = "1111";

    @DisplayInfo(label = "Control Low Level Discriminator", desc = "Primary pillar lower energy discriminator threshold")
    public double controlLowLevelDiscriminator = 0.069;

    @DisplayInfo(label = "Control Upper Level Discriminator", desc = "Primary pillar upper energy discriminator threshold")
    public double controlUpperLevelDiscriminator = 0.455;

    @DisplayInfo(label = "Relay Output", desc = "Relay output configuration")
    public int relayOutput = 1;

    @DisplayInfo(label = "Algorithm", desc = "Detector alarm calculation algorithm mask")
    public String algorithm = "1001";

    @DisplayInfo(label = "Software Version", desc = "RPM gamma software version code")
    public String softwareVersion = "A";

    @DisplayInfo(label = "Slave Low Level Discriminator", desc = "Secondary pillar lower energy discriminator threshold")
    public double slaveLowLevelDiscriminator = 0.069;

    @DisplayInfo(label = "Slave Upper Level Discriminator", desc = "Secondary pillar upper energy discriminator threshold")
    public double slaveUpperLevelDiscriminator = 0.455;

    @DisplayInfo(label = "Background Time", desc = "Gamma background averaging time (seconds)")
    public int backgroundTime = 20;

    @DisplayInfo(label = "Background Sigma", desc = "Background sigma value for throw-through alarm")
    public double backgroundSigma = 0.0;

    @DisplayInfo(label = "Firmware Version", desc = "RPM firmware version")
    public String firmwareVersion = "1.10.1";

    /**
     * Applies parameters from an {@code SG1} daily-file message line.
     *
     * @param sg1 tokenized {@code SG1} CSV line
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applySetupGamma1(String[] sg1) {
        if (sg1 == null || sg1.length < 6) {
            return false;
        }
        int newHighBg = Integer.parseInt(sg1[1].trim());
        int newLowBg = Integer.parseInt(sg1[2].trim());
        int newIntervals = Integer.parseInt(sg1[3].trim());
        int newHoldin = Integer.parseInt(sg1[4].trim());
        double newNsigma = Double.parseDouble(sg1[5].trim());

        boolean changed = this.highBackgroundFault != newHighBg
                || this.lowBackgroundFault != newLowBg
                || this.intervals != newIntervals
                || this.occupancyHoldin != newHoldin
                || Double.compare(this.nsigma, newNsigma) != 0;

        this.highBackgroundFault = newHighBg;
        this.lowBackgroundFault = newLowBg;
        this.intervals = newIntervals;
        this.occupancyHoldin = newHoldin;
        this.nsigma = newNsigma;
        return changed;
    }

    /**
     * Applies parameters from an {@code SG2} daily-file message line.
     *
     * @param sg2 tokenized {@code SG2} CSV line
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applySetupGamma2(String[] sg2) {
        if (sg2 == null || sg2.length < 7) {
            return false;
        }
        String newDetectors = sg2[1].trim();
        double newControlLLD = Double.parseDouble(sg2[2].trim());
        double newControlULD = Double.parseDouble(sg2[3].trim());
        int newRelayOutput = Integer.parseInt(sg2[4].trim());
        String newAlgorithm = sg2[5].trim();
        String newSoftwareVersion = sg2[6].trim();

        boolean changed = !Objects.equals(this.detectors, newDetectors)
                || Double.compare(this.controlLowLevelDiscriminator, newControlLLD) != 0
                || Double.compare(this.controlUpperLevelDiscriminator, newControlULD) != 0
                || this.relayOutput != newRelayOutput
                || !Objects.equals(this.algorithm, newAlgorithm)
                || !Objects.equals(this.softwareVersion, newSoftwareVersion);

        this.detectors = newDetectors;
        this.controlLowLevelDiscriminator = newControlLLD;
        this.controlUpperLevelDiscriminator = newControlULD;
        this.relayOutput = newRelayOutput;
        this.algorithm = newAlgorithm;
        this.softwareVersion = newSoftwareVersion;
        return changed;
    }

    /**
     * Applies parameters from an {@code SG3} daily-file message line.
     *
     * @param sg3 tokenized {@code SG3} CSV line
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applySetupGamma3(String[] sg3) {
        if (sg3 == null || sg3.length < 6) {
            return false;
        }
        double newSlaveLLD = Double.parseDouble(sg3[1].trim());
        double newSlaveULD = Double.parseDouble(sg3[2].trim());
        int newBackgroundTime = Integer.parseInt(sg3[3].trim());
        double newBackgroundSigma = Double.parseDouble(sg3[4].trim());
        String newFirmwareVersion = sg3[5].trim();

        boolean changed = Double.compare(this.slaveLowLevelDiscriminator, newSlaveLLD) != 0
                || Double.compare(this.slaveUpperLevelDiscriminator, newSlaveULD) != 0
                || this.backgroundTime != newBackgroundTime
                || Double.compare(this.backgroundSigma, newBackgroundSigma) != 0
                || !Objects.equals(this.firmwareVersion, newFirmwareVersion);

        this.slaveLowLevelDiscriminator = newSlaveLLD;
        this.slaveUpperLevelDiscriminator = newSlaveULD;
        this.backgroundTime = newBackgroundTime;
        this.backgroundSigma = newBackgroundSigma;
        this.firmwareVersion = newFirmwareVersion;
        return changed;
    }

    /**
     * Applies all three gamma setup lines ({@code SG1}, {@code SG2}, {@code SG3}).
     *
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applyFromStream(String[] sg1, String[] sg2, String[] sg3) {
        boolean c1 = applySetupGamma1(sg1);
        boolean c2 = applySetupGamma2(sg2);
        boolean c3 = applySetupGamma3(sg3);
        return c1 || c2 || c3;
    }
}
