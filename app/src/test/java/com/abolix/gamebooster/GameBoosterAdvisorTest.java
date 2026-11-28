package com.abolix.gamebooster;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GameBoosterAdvisorTest {
    @Test public void severeThermalStateAlwaysPrioritizesCooling() {
        assertEquals(GameBoosterAdvisor.Profile.COOL_DOWN,
                GameBoosterAdvisor.recommend(3, 8L * 1024L * 1024L * 1024L, false, false, 95));
    }

    @Test public void powerSaverOrLowBatterySelectsPowerSavingProfile() {
        assertEquals(GameBoosterAdvisor.Profile.POWER_SAVER,
                GameBoosterAdvisor.recommend(0, 4L * 1024L * 1024L * 1024L, false, true, 80));
        assertEquals(GameBoosterAdvisor.Profile.POWER_SAVER,
                GameBoosterAdvisor.recommend(0, 4L * 1024L * 1024L * 1024L, false, false, 10));
    }

    @Test public void moderateThermalOrLowMemorySelectsBalancedProfile() {
        assertEquals(GameBoosterAdvisor.Profile.BALANCED,
                GameBoosterAdvisor.recommend(2, 4L * 1024L * 1024L * 1024L, false, false, 70));
        assertEquals(GameBoosterAdvisor.Profile.BALANCED,
                GameBoosterAdvisor.recommend(0, 512L * 1024L * 1024L, false, false, 70));
        assertEquals(GameBoosterAdvisor.Profile.BALANCED,
                GameBoosterAdvisor.recommend(0, 4L * 1024L * 1024L * 1024L, true, false, 70));
    }

    @Test public void healthyDeviceReceivesPerformanceRecommendation() {
        assertEquals(GameBoosterAdvisor.Profile.PERFORMANCE,
                GameBoosterAdvisor.recommend(0, 3L * 1024L * 1024L * 1024L, false, false, 70));
    }

    @Test public void unavailableReadingsUseAConservativeBalancedProfile() {
        assertEquals(GameBoosterAdvisor.Profile.BALANCED,
                GameBoosterAdvisor.recommend(-1, 0L, false, false, -1));
        assertEquals(GameBoosterAdvisor.Profile.PERFORMANCE,
                GameBoosterAdvisor.recommend(-1, 2L * 1024L * 1024L * 1024L, false, false, -1));
    }
}
