package org.flexiblepower.simulation.usb_charger;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Power;
import javax.measure.unit.SI;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.flexiblepower.context.FlexiblePowerContext;
import org.flexiblepower.messaging.Endpoint;
import org.flexiblepower.observation.Observation;
import org.flexiblepower.observation.ext.SimpleObservationProvider;
import org.flexiblepower.ral.ResourceControlParameters;
import org.flexiblepower.ral.drivers.uncontrolled.PowerState;
import org.flexiblepower.ral.drivers.uncontrolled.UncontrollableDriver;
import org.flexiblepower.ral.ext.AbstractResourceDriver;
import org.flexiblepower.simulation.usb_charger.Usb_Charger.Config;
import org.flexiblepower.ui.Widget;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Reference;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

@Component(designateFactory = Config.class, provide = Endpoint.class, immediate = true)
public class Usb_Charger extends AbstractResourceDriver<PowerState, ResourceControlParameters>
                         implements
                         UncontrollableDriver,
                         Runnable,
                         MqttCallback {

    public final static class PowerStateImpl implements PowerState {
        private final Measurable<Power> demand;

        private final Date currentTime;

        private PowerStateImpl(Measurable<Power> demand, Date currentTime) {
            this.demand = demand;
            this.currentTime = currentTime;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public Measurable<Power> getCurrentUsage() {
            return demand;
        }

        public Date getTime() {
            return currentTime;
        }

        @Override
        public String toString() {
            return "PowerStateImpl [demand=" + demand + ", currentTime=" + currentTime + "]";
        }
    }

    @Meta.OCD
    interface Config {
        @Meta.AD(deflt = "1", description = "Delay between updates will be send out in seconds")
            int updateDelay();

        @Meta.AD(deflt = "usb_charger", description = "Resource identifier")
               String resourceId();

        @Meta.AD(deflt = "tcp://130.211.82.48:1883", description = "URL to the MQTT broker")
               String brokerUrl();

        @Meta.AD(deflt = "/FpaiUsbChargerRequest", description = "Mqtt request topic to zenobox")
               String usbchargerMqttRequestTopic();

        @Meta.AD(deflt = "/FpaiUsbChargerResponse", description = "Mqtt response topic to zenobox")
               String usbchargerMqttResponseTopic();

        @Meta.AD(deflt = "/FpaiUsbchargerOnRequest", description = "Mqtt request topic to zenobox")
               String usbchargerOnMqttRequestTopic();

        @Meta.AD(deflt = "/FpaiUsbchargerOffRequest", description = "Mqtt request topic to zenobox")
               String usbchargerOffMqttRequestTopic();
    }

    private MqttClient mqttClient;
    public double demand = -0.01;
    private int updateDelay = 0;

    private Usb_ChargerWidget widget;
    private ScheduledFuture<?> scheduledFuture;
    private ServiceRegistration<Widget> widgetRegistration;
    private Config config;
    private SimpleObservationProvider<PowerState> observationProvider;

    @Override
    public synchronized void run() {
        try {
            publishState(getCurrentState());
            observationProvider.publish(Observation.create(context.currentTime(),
                                                           getCurrentState()));

        } catch (Exception e) {
            logger.error("Error while running UsbChargerSimulation", e);
        }
    }

    @Activate
    public void activate(BundleContext bundleContext, Map<String, Object> properties) throws MqttException {

        try {
            config = Configurable.createConfigurable(Config.class, properties);
            updateDelay = config.updateDelay();

            if (mqttClient == null) {
                mqttClient = new MqttClient(config.brokerUrl(), UUID.randomUUID().toString());
                mqttClient.setCallback(this);
                mqttClient.connect();

                mqttClient.subscribe(config.usbchargerMqttResponseTopic());
            }

            observationProvider = SimpleObservationProvider.create(this, PowerState.class)
                                                           .observationOf("simulated usb charger")
                                                           .build();
            scheduledFuture = context.scheduleAtFixedRate(this,
                                                          Measure.valueOf(0, SI.SECOND),
                                                          Measure.valueOf(updateDelay, SI.SECOND));
            widget = new Usb_ChargerWidget(this);
            widgetRegistration = bundleContext.registerService(Widget.class, widget, null);
        } catch (RuntimeException ex) {
            logger.error("Error during initialization of the usb charger simulation: " + ex.getMessage(), ex);
            deactivate();
            throw ex;
        }
    }

    // *************MQTT CALLBACK METHODS START**********************
    @Override
    public void connectionLost(Throwable arg0) {
        try {
            if (!mqttClient.isConnected()) {
                mqttClient.connect();
                mqttClient.subscribe(config.usbchargerMqttResponseTopic());
            }
        } catch (MqttException e) {

        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken arg0) {

    }

    @Override
    public void messageArrived(String arg0, MqttMessage arg1) throws Exception {

        if (arg0.equals(config.usbchargerMqttResponseTopic())) {
            logger.info("ZENODYS USBCHARGER : " + arg1.toString());
            double current = 3000 * Double.valueOf(arg1.toString()) - 210;

            demand = current * 12;
            if (demand <= 100) {
                demand = 0;
            }
        }
    }
    // *************MQTT CALLBACK METHODS END**********************

    @Deactivate
    public void deactivate() {
        if (observationProvider != null) {
            observationProvider.close();
            observationProvider = null;
        }
        if (widgetRegistration != null) {
            widgetRegistration.unregister();
            widgetRegistration = null;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    private FlexiblePowerContext context;

    @Reference
    public void setContext(FlexiblePowerContext context) {

        this.context = context;
    }

    @Override
    protected void handleControlParameters(ResourceControlParameters controlParameters) {
        // Will never be called!
        throw new AssertionError();
    }

    double roundTwoDecimals(double d) {
        DecimalFormat twoDForm = new DecimalFormat("#.##");
        return Double.valueOf(twoDForm.format(d));
    }

    // int currentValue = 0;

    protected PowerStateImpl getCurrentState() {
        return new PowerStateImpl(Measure.valueOf(demand, SI.WATT), context.currentTime());
    }

    public int GetCurrentConsumption() {
        return (int) demand;
    }

    public void setChargerState(final boolean aState) throws MqttPersistenceException, MqttException {
        MqttMessage msg = new MqttMessage();
        String dd = aState ? "1" : "0";

        msg.setPayload(dd.getBytes());
        if (aState) {

            mqttClient.publish(config.usbchargerOnMqttRequestTopic(), msg);
        } else {

            mqttClient.publish(config.usbchargerOffMqttRequestTopic(), msg);
        }
    }
}