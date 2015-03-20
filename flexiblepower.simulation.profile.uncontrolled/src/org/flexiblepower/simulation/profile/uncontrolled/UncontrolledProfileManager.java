package org.flexiblepower.simulation.profile.uncontrolled;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import javax.measure.Measure;
import javax.measure.unit.SI;

import org.flexiblepower.context.FlexiblePowerContext;
import org.flexiblepower.efi.UncontrolledResourceManager;
import org.flexiblepower.efi.uncontrolled.UncontrolledMeasurement;
import org.flexiblepower.efi.uncontrolled.UncontrolledRegistration;
import org.flexiblepower.messaging.Connection;
import org.flexiblepower.messaging.Endpoint;
import org.flexiblepower.messaging.MessageHandler;
import org.flexiblepower.ral.values.CommodityMeasurables;
import org.flexiblepower.ral.values.CommoditySet;
import org.flexiblepower.ral.values.ConstraintListMap;
import org.flexiblepower.simulation.profile.uncontrolled.UncontrolledProfileManager.Config;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Reference;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

@Component(designateFactory = Config.class, provide = Endpoint.class, immediate = true)
public class UncontrolledProfileManager implements UncontrolledResourceManager, Runnable, MessageHandler {

    @Meta.OCD
    interface Config {
        @Meta.AD(deflt = "uncontrolledprofilemanager", description = "Resource identifier")
        String resourceId();

        @Meta.AD(deflt = "pv.csv", description = "CSV file with power data profile")
        String filename();

        @Meta.AD(deflt = "true", description = "Generates power [true] or consumes power [false]")
        boolean generatesPower();

        @Meta.AD(deflt = "5", description = "Delay between updates will be send out in seconds")
        int updateDelay();
    }

    private static final int YEAR = 2012;
    private static final int DAYS_IN_YEAR = 366;
    private static final int HOURS_IN_DAY = 24;
    private static final int MINUTES_IN_HOUR = 60;

    private static final Logger logger = LoggerFactory.getLogger(UncontrolledProfileManager.class);

    private Config config;
    private Connection connection;
    private FlexiblePowerContext context;
    private ScheduledFuture<?> scheduledFuture;
    private float[] powerAtMinutesSinceJan1;

    @Activate
    public void activate(BundleContext bundleContext, Map<String, Object> properties) throws IOException {
        try {
            config = Configurable.createConfigurable(Config.class, properties);

            try {
                File file = new File(config.filename()); // For running from current directory
                if (file.exists() && file.isFile()) {
                    loadData(new FileInputStream(file));
                } else {
                    file = new File("res/" + config.filename()); // For running in Eclipse
                    if (file.exists() && file.isFile()) {
                        loadData(new FileInputStream(file));
                    } else {
                        URL url = bundleContext.getBundle().getResource(config.filename());
                        if (url != null) {
                            loadData(url.openStream());
                        } else {
                            throw new IllegalArgumentException("Could not load power profile data");
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Could not load power profile data", e);
                throw (e);
            }

            scheduledFuture = context.scheduleAtFixedRate(this,
                                                          Measure.valueOf(0, SI.SECOND),
                                                          Measure.valueOf(config.updateDelay(), SI.SECOND));
        } catch (RuntimeException ex) {
            logger.error("Error during initialization of the uncontrolled profile manager: " + ex.getMessage(), ex);
            deactivate();
            throw ex;
        }
    }

    @Deactivate
    public void deactivate() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public void disconnected() {
        connection = null;
    }

    @Override
    public void handleMessage(Object message) {
        // We do not expect messages
    }

    @Override
    public MessageHandler onConnect(Connection connection) {
        this.connection = connection;
        connection.sendMessage(new UncontrolledRegistration(config.resourceId(),
                                                            context.currentTime(),
                                                            Measure.zero(SI.SECOND),
                                                            CommoditySet.onlyElectricity,
                                                            ConstraintListMap.create().build()));
        return this;
    }

    private double interpolate(float value1, float value2, double fraction) {
        return (1 - fraction) * value1 + fraction * value2;
    }

    private void loadData(InputStream is) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));

        powerAtMinutesSinceJan1 = new float[DAYS_IN_YEAR * HOURS_IN_DAY * MINUTES_IN_HOUR];
        String line = null;
        while ((line = bufferedReader.readLine()) != null) {
            if (!line.startsWith("#")) { // Line does not contain comment
                String[] split = line.split(",");
                if (split.length == 5) {
                    int month = Integer.parseInt(split[0].trim());
                    int day = Integer.parseInt(split[1].trim());
                    int hour = Integer.parseInt(split[2].trim());
                    int minute = Integer.parseInt(split[3].trim());
                    float powerValue = Float.parseFloat(split[4].trim());

                    int index = minutesSinceJan1(month, day, hour, minute);
                    powerAtMinutesSinceJan1[index] = powerValue;
                }
            }
        }
        bufferedReader.close();
    }

    private int minutesSinceJan1(int month, int day, int hour, int minute) {
        long millisecondsAtJan1 = 0;
        try {
            millisecondsAtJan1 = new SimpleDateFormat("yyyy-dd-MM HH:mm:ss").parse(YEAR + "-01-01 00:00:00").getTime();
        } catch (ParseException e) {
        }
        Calendar calendar = new GregorianCalendar();
        calendar.set(YEAR, month - 1, day, hour, minute);
        long milliseconds = calendar.getTime().getTime();
        int minutesSinceJan1 = (int) ((milliseconds - millisecondsAtJan1) / 60000);
        return minutesSinceJan1;
    }

    @Override
    public synchronized void run() {
        try {
            if (connection != null) {
                Calendar calendar = new GregorianCalendar();
                calendar.setTime(context.currentTime());
                int month = calendar.get(Calendar.MONTH) + 1;
                int day = calendar.get(Calendar.DAY_OF_MONTH);
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                int minute = calendar.get(Calendar.MINUTE);
                int second = calendar.get(Calendar.SECOND);

                int minutesSinceJan1 = minutesSinceJan1(month, day, hour, minute);
                float powerValue1 = powerAtMinutesSinceJan1[minutesSinceJan1];
                float powerValue2 = powerAtMinutesSinceJan1[(minutesSinceJan1 + 1) % powerAtMinutesSinceJan1.length];
                double interpolatedPowerValue = interpolate(powerValue1, powerValue2, second / 60.0);
                if (config.generatesPower()) {
                    interpolatedPowerValue = -interpolatedPowerValue;
                }
                connection.sendMessage(new UncontrolledMeasurement(config.resourceId(),
                                                                   context.currentTime(),
                                                                   context.currentTime(),
                                                                   CommodityMeasurables.create()
                                                                                       .electricity(Measure.valueOf(interpolatedPowerValue,
                                                                                                                    SI.WATT))
                                                                                       .build()));
            }
        } catch (Exception e) {
            logger.error("Error while running uncontrolled profile manager", e);
        }
    }

    @Reference
    public void setContext(FlexiblePowerContext context) {
        this.context = context;
    }
}