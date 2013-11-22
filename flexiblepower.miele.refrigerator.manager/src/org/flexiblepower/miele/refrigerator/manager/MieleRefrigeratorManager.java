package org.flexiblepower.miele.refrigerator.manager;

import static javax.measure.unit.NonSI.HOUR;
import static javax.measure.unit.SI.CELSIUS;
import static javax.measure.unit.SI.JOULE;
import static javax.measure.unit.SI.KILO;
import static javax.measure.unit.SI.WATT;

import java.util.Date;
import java.util.Map;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;

import org.flexiblepower.miele.refrigerator.manager.MieleRefrigeratorManager.Config;
import org.flexiblepower.observation.Observation;
import org.flexiblepower.observation.ObservationProvider;
import org.flexiblepower.rai.Allocation;
import org.flexiblepower.rai.BufferControlSpace;
import org.flexiblepower.rai.values.ConstraintList;
import org.flexiblepower.ral.ResourceManager;
import org.flexiblepower.ral.drivers.refrigerator.RefrigeratorControlParameters;
import org.flexiblepower.ral.drivers.refrigerator.RefrigeratorDriver;
import org.flexiblepower.ral.drivers.refrigerator.RefrigeratorState;
import org.flexiblepower.ral.ext.AbstractResourceManager;
import org.flexiblepower.time.TimeService;
import org.flexiblepower.time.TimeUtil;
import org.flexiblepower.ui.Widget;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Reference;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

@Component(designateFactory = Config.class, provide = ResourceManager.class)
public class MieleRefrigeratorManager extends
                                     AbstractResourceManager<BufferControlSpace, RefrigeratorState, RefrigeratorControlParameters> {
    @Meta.OCD
    interface Config {
        @Meta.AD(deflt = "refrigerator")
        String resourceId();
    }

    public MieleRefrigeratorManager() {
        super(RefrigeratorDriver.class, BufferControlSpace.class);
    }

    private Config config;
    private RefrigeratorWidget widget;
    private ServiceRegistration<Widget> widgetRegistration;

    @Activate
    public void activate(BundleContext context, Map<String, Object> properties) {
        try {
            config = Configurable.createConfigurable(Config.class, properties);
            widget = new RefrigeratorWidget(this);
            widgetRegistration = context.registerService(Widget.class, widget, null);
        } catch (RuntimeException ex) {
            logger.error("Error during activation of the MieleRefrigeratorManager", ex);
            deactivate();
            throw ex;
        }
    }

    @Deactivate
    public void deactivate() {
        widgetRegistration.unregister();
        widget = null;
    }

    private TimeService timeService;

    @Reference
    public void setTimeService(TimeService timeService) {
        this.timeService = timeService;
    }

    private volatile RefrigeratorState currentState;

    @Override
    public void consume(ObservationProvider<? extends RefrigeratorState> source,
                        Observation<? extends RefrigeratorState> observation) {

        logger.debug("Observation consumede by miele refr");

        // store latest state (volatile, use only for single write in this method)
        currentState = observation.getValue();

        RefrigeratorState state = observation.getValue();
        double maxTemp = state.getTargetTemperature().doubleValue(CELSIUS);
        double minTemp = state.getMinimumTemperature().doubleValue(CELSIUS);
        double curTemp = state.getCurrentTemperature().doubleValue(CELSIUS);

        // Calculate state of charge
        double stateOfCharge = (maxTemp - curTemp) / (maxTemp - minTemp);

        // Create control space
        Measurable<Energy> totalCapacity = Measure.valueOf(10, KILO(JOULE));
        ConstraintList<Power> chargeSpeed = ConstraintList.create(WATT).addSingle(300).build();
        Measurable<Power> selfDischarge = Measure.valueOf(50, WATT);

        Measurable<Duration> minOnPeriod = Measure.zero();
        Measurable<Duration> minOffPeriod = Measure.zero();

        // Optional parameters, null if undefined
        Date targetTime = null;
        Double targetStateOfCharge = null;

        // Time
        Date validFrom = timeService.getTime();
        Date validThru = TimeUtil.add(timeService.getTime(), Measure.valueOf(24, HOUR));
        Date expirationTime = validThru;

        // Return the Buffercontrolspace
        publish(new BufferControlSpace(config.resourceId(),
                                       validFrom,
                                       validThru,
                                       expirationTime,
                                       totalCapacity,
                                       stateOfCharge,
                                       chargeSpeed,
                                       selfDischarge,
                                       minOnPeriod,
                                       minOffPeriod,
                                       targetTime,
                                       targetStateOfCharge));
    }

    @Override
    public void handleAllocation(Allocation allocation) {
        Date startPeriod = allocation.getStartTime();

        Date now = timeService.getTime();
        /**
         * Allocation converter Logic
         */
        if (startPeriod.before(now) || allocation.getEnergyProfile().getTotalEnergy().longValue(JOULE) == 0) {
            superCool(false);
        } else {
            superCool(true);
        }
    }

    public void superCool(final boolean state) {
        RefrigeratorState fridgeState = getCurrentState();
        if (fridgeState != null) {
            if (fridgeState.getSupercoolMode() ^ state) {
                getDriver().setControlParameters(new RefrigeratorControlParameters() {
                    @Override
                    public boolean getSupercoolMode() {
                        return state;
                    }
                });
            }
        }
    }

    protected RefrigeratorState getCurrentState() {
        return currentState;
    }
}
