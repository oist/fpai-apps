package nl.tno.fpai.monitoring.test;

import java.util.Date;

import javax.measure.Measurable;
import javax.measure.Measure;
import javax.measure.quantity.Power;
import javax.measure.unit.SI;

import org.flexiblepower.observation.Observation;
import org.flexiblepower.observation.ext.AbstractObservationProvider;
import org.flexiblepower.observation.ext.ObservationProviderRegistrationHelper;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;

/**
 * Observation Provider with some dummy values ...
 */
@Component(immediate = true, designateFactory = DummyObservationProvider.Config.class)
public class DummyObservationProvider extends AbstractObservationProvider<SomeValues> {
    private interface Config {
        String dummyValue();
    }

    private String by = "some-observer-" + ((int) (Math.random() * 10000));
    private String of = "something-" + ((int) (Math.random() * 10000));
    private Thread thread;

    @Activate
    public void activate() {
        new ObservationProviderRegistrationHelper(this).observationOf(this.of)
                                                       .observedBy(this.by)
                                                       .observationType(SomeValues.class)
                                                       .register();

        this.thread = new Thread("thread-for-observation-provider-" + this.by) {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep((long) (10 + Math.random() * 10));
                    } catch (InterruptedException e) {
                    }

                    DummyObservationProvider.this.publish(new Observation<SomeValues>(new Date(), new SomeValues() {
                        double v1 = Math.random() * 10 + 10;
                        String v2 = Integer.toHexString((int) (Math.random() * 10000));

                        @Override
                        public String getValue2() {
                            return this.v2;
                        }

                        @Override
                        public double getValue1() {
                            return this.v1;
                        }

                        @Override
                        public Measurable<Power> getValue3() {
                            return Measure.valueOf(this.v1, SI.WATT);
                        }
                    }));
                }
            }
        };
        this.thread.start();
    }

    @Deactivate
    public void deactivate() {
        try {
            this.thread.interrupt();
            this.thread.join();
        } catch (InterruptedException e) {
        } finally {
            this.thread = null;
        }
    }
}
