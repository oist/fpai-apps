package org.flexiblepower.simulation.rex;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import javax.measure.unit.SI;

import org.flexiblepower.simulation.rex.REXSimulation.PowerStateImpl;
import org.flexiblepower.ui.Widget;

public class REXWidget implements Widget {
    public static class Update {
        private final String dateTime;
        private final String supply;
        private final String weather;

        public Update(String dateTime, String supply, String weather) {
            this.dateTime = dateTime;
            this.supply = supply;
            this.weather = weather;
        }

        public String getDateTime() {
            return dateTime;
        }

        public String getSupply() {
            return supply;
        }

        public String getWeather() {
            return weather;
        }
    }

    private static final DateFormat FORMATTER = new SimpleDateFormat("HH:mm:ss");

    private final REXSimulation simulation;

    public REXWidget(REXSimulation simulation) {
        this.simulation = simulation;
    }

    public Update update() {
        PowerStateImpl state = simulation.getCurrentState();
        return new Update(FORMATTER.format(state.getTime()),
                          Integer.toString((int) state.getCurrentUsage().doubleValue(SI.WATT)),
                          String.valueOf(simulation.demand));
    }

    public Update changeWeather() {

        return update();
    }

    @Override
    public String getTitle(Locale locale) {
        return "REX Controller";
    }
}