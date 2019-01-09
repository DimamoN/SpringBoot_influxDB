package com.dimamon.service;


import com.dimamon.entities.WorkloadPoint;
import com.dimamon.repo.MeasurementsRepo;
import com.dimamon.service.kubernetes.KubernetesService;
import com.dimamon.service.predict.PredictorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.RandomAccess;
import java.util.stream.Collectors;

import static com.dimamon.utils.StringUtils.showValue;


/**
 * Periodically check database with metrics to make workload prediction and make scaling decision
 */
@Service
public class ScaleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScaleService.class);

    private static final int INITIAL_DELAY = 10 * 1000; // 10 sec
    private static final int CHECK_EVERY = 30 * 1000; // 30 sec

    /**
     * 1 unit is 10 seconds, so 6 * 5 = 1 min
     */
    private static final int FORECAST_FOR = 6 * 2; // 2 minutes
    private static final int LAST_METRICS_COUNT = 6 * 2; // 2 minutes

    private static final int SCALE_UP_THRESHOLD = 80;
    private static final int SCALE_DOWN_THRESHOLD = 25;

    private static final String APP_NAME = "scaler-app";

    @Autowired
    private MeasurementsRepo measurementsRepo;

    @Qualifier("esPredictor")
    @Autowired
    private PredictorService predictorService;

    @Autowired
    private KubernetesService kubernetesService;

    @Scheduled(initialDelay = INITIAL_DELAY, fixedDelay = CHECK_EVERY)
    public void checkMetrics() {
        LOGGER.info("### Checking metrics task = {}", new Date());
        kubernetesService.checkPods();

        List<Double> cpuMeasurements = measurementsRepo.getLastLoadMetrics(LAST_METRICS_COUNT)
                .stream().map(WorkloadPoint::getPodCpu)
                .collect(Collectors.toList());

        double avgPrediction = predictorService.averagePrediction(FORECAST_FOR, cpuMeasurements);
        LOGGER.info("Avg pred {} = {}", avgPrediction, avgPrediction);

        measurementsRepo.writePrediction(APP_NAME, avgPrediction);
        measurementsRepo.writePodCount(APP_NAME, kubernetesService.getMetricsPodCount());

        if (shouldScaleUp(avgPrediction)) {
            LOGGER.info("Avg prediction {}% > {}%", showValue(avgPrediction), SCALE_UP_THRESHOLD);
            kubernetesService.scaleUpService();
        } else if (shouldScaleDown(avgPrediction)) {
            LOGGER.info("Avg prediction {}% < {}%", showValue(avgPrediction), SCALE_DOWN_THRESHOLD);
            kubernetesService.scaleDownService();
        } else {
            LOGGER.info("Avg prediction {}%, no need to scale", showValue(avgPrediction));
        }
    }

    private boolean shouldScaleUp(double predictedWorkload) {
        return predictedWorkload > SCALE_UP_THRESHOLD;
    }

    private boolean shouldScaleDown(double predictedWorkload) {
        return predictedWorkload < SCALE_DOWN_THRESHOLD;
    }

}
