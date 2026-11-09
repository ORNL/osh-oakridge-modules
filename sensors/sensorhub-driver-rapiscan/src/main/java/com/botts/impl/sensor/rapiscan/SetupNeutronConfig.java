package com.botts.impl.sensor.rapiscan;

import org.sensorhub.api.config.DisplayInfo;

public class SetupNeutronConfig {

    @DisplayInfo(label = "High Background Fault", desc = "Neutron high background fault threshold (cps)")
    public int highBackgroundFault = 50;

    @DisplayInfo(label = "Maximum Intervals", desc = "Neutron maximum measurement intervals")
    public int maxIntervals = 3;

    @DisplayInfo(label = "Alpha Value", desc = "Neutron alpha value used in calculations")
    public double alphaValue = 4.0;

    @DisplayInfo(label = "ZMax Value", desc = "Maximum neutron Z-value threshold")
    public double zMaxValue = 1200.0;

    @DisplayInfo(label = "Sequential Intervals", desc = "Number of sequential neutron intervals")
    public int sequentialIntervals = 1;

    @DisplayInfo(label = "Background Time", desc = "Neutron background averaging time (seconds)")
    public int backgroundTime = 120;

    @DisplayInfo(label = "Control Low Level Discriminator", desc = "Primary pillar neutron lower energy discriminator threshold")
    public double controlLowLevelDiscriminator = 0.504;

    @DisplayInfo(label = "Control Upper Level Discriminator", desc = "Primary pillar neutron upper energy discriminator threshold")
    public double controlUpperLevelDiscriminator = 5.040;

    @DisplayInfo(label = "Slave Low Level Discriminator", desc = "Secondary pillar neutron lower energy discriminator threshold")
    public double slaveLowLevelDiscriminator = 0.504;

    @DisplayInfo(label = "Slave Upper Level Discriminator", desc = "Secondary pillar neutron upper energy discriminator threshold")
    public double slaveUpperLevelDiscriminator = 5.040;

    /**
     * Applies parameters from an {@code SN1} daily-file message line.
     *
     * @param sn1 tokenized {@code SN1} CSV line
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applySetupNeutron1(String[] sn1) {
        if (sn1 == null || sn1.length < 7) {
            return false;
        }
        int newHighBg = Integer.parseInt(sn1[1].trim());
        int newMaxIntervals = Integer.parseInt(sn1[2].trim());
        double newAlphaValue = Double.parseDouble(sn1[3].trim());
        double newZMaxValue = Double.parseDouble(sn1[4].trim());
        int newSeqIntervals = Integer.parseInt(sn1[5].trim());
        int newBgTime = Integer.parseInt(sn1[6].trim());

        boolean changed = this.highBackgroundFault != newHighBg
                || this.maxIntervals != newMaxIntervals
                || Double.compare(this.alphaValue, newAlphaValue) != 0
                || Double.compare(this.zMaxValue, newZMaxValue) != 0
                || this.sequentialIntervals != newSeqIntervals
                || this.backgroundTime != newBgTime;

        this.highBackgroundFault = newHighBg;
        this.maxIntervals = newMaxIntervals;
        this.alphaValue = newAlphaValue;
        this.zMaxValue = newZMaxValue;
        this.sequentialIntervals = newSeqIntervals;
        this.backgroundTime = newBgTime;
        return changed;
    }

    /**
     * Applies parameters from an {@code SN2} daily-file message line.
     *
     * @param sn2 tokenized {@code SN2} CSV line
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applySetupNeutron2(String[] sn2) {
        if (sn2 == null || sn2.length < 5) {
            return false;
        }
        double newControlLLD = Double.parseDouble(sn2[1].trim());
        double newControlULD = Double.parseDouble(sn2[2].trim());
        double newSlaveLLD = Double.parseDouble(sn2[3].trim());
        double newSlaveULD = Double.parseDouble(sn2[4].trim());

        boolean changed = Double.compare(this.controlLowLevelDiscriminator, newControlLLD) != 0
                || Double.compare(this.controlUpperLevelDiscriminator, newControlULD) != 0
                || Double.compare(this.slaveLowLevelDiscriminator, newSlaveLLD) != 0
                || Double.compare(this.slaveUpperLevelDiscriminator, newSlaveULD) != 0;

        this.controlLowLevelDiscriminator = newControlLLD;
        this.controlUpperLevelDiscriminator = newControlULD;
        this.slaveLowLevelDiscriminator = newSlaveLLD;
        this.slaveUpperLevelDiscriminator = newSlaveULD;
        return changed;
    }

    /**
     * Applies both neutron setup lines ({@code SN1}, {@code SN2}).
     *
     * @return {@code true} if at least one parameter value changed
     */
    public synchronized boolean applyFromStream(String[] sn1, String[] sn2) {
        boolean c1 = applySetupNeutron1(sn1);
        boolean c2 = applySetupNeutron2(sn2);
        return c1 || c2;
    }
}
