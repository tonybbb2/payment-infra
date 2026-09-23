import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const workflowSucceeded =
    new Counter('payment_workflows_succeeded');

const workflowFailed =
    new Counter('payment_workflows_failed');

const workflowSuccessRate =
    new Rate('workflow_success_rate');

const BASE_URL =
    'http://localhost:8081';

const RATE =
    Number(__ENV.RATE || 100);

const DURATION =
    __ENV.DURATION || '2m';

export const options = {
    scenarios: {
        payments: {
            executor: 'constant-arrival-rate',

            rate: RATE,

            timeUnit: '1s',

            duration: DURATION,

            preAllocatedVUs: 100,

            maxVUs: 500,
        },
    },

    thresholds: {
        http_req_failed: [
            'rate<0.01',
        ],

        http_req_duration: [
            'p(95)<500',
            'p(99)<1000',
        ],

        workflow_success_rate: [
            'rate>0.99',
        ],

        dropped_iterations: [
            'count==0',
        ],
    },
};

export function setup() {

    const response = http.post(
        `${BASE_URL}/processor/mode/SUCCESS`,
        null,
        {
            tags: {
                name: 'processor_mode_success',
            },
        }
    );

    check(response, {
        'processor set to SUCCESS': (r) =>
            r.status >= 200 &&
            r.status < 300,
    });
}

export default function () {

    const idempotencyKey =
        `capacity-${__VU}-${__ITER}-${Date.now()}`;

    const amount =
        (
            Math.floor(
                Math.random() * 49001
            ) + 100
        ) / 100;

    const payload = JSON.stringify({
        amount: amount,
        currency: 'CAD',
    });

    const createResponse = http.post(
        `${BASE_URL}/payments`,
        payload,
        {
            headers: {
                'Content-Type':
                    'application/json',

                'Idempotency-Key':
                    idempotencyKey,
            },

            tags: {
                name: 'create_payment',
            },
        }
    );

    const created = check(
        createResponse,
        {
            'payment creation succeeds': (r) =>
                r.status === 200 ||
                r.status === 201,
        }
    );

    if (!created) {

        workflowFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    let payment;

    try {

        payment =
            createResponse.json();

    } catch {

        workflowFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    const paymentId =
        payment.id;

    sleep(
        Math.random() * 0.1
    );

    const authorizeResponse = http.post(
        `${BASE_URL}/payments/${paymentId}/authorize`,
        null,
        {
            tags: {
                name: 'authorize_payment',
            },
        }
    );

    const authorized = check(
        authorizeResponse,
        {
            'authorization succeeds': (r) =>
                r.status >= 200 &&
                r.status < 300,
        }
    );

    if (!authorized) {

        workflowFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    /*
     * Roughly 30% of payments continue
     * to capture.
     */
    if (Math.random() < 0.30) {

        sleep(
            Math.random() * 0.2
        );

        const captureResponse = http.post(
            `${BASE_URL}/payments/${paymentId}/capture`,
            null,
            {
                tags: {
                    name: 'capture_payment',
                },
            }
        );

        const captured = check(
            captureResponse,
            {
                'capture succeeds': (r) =>
                    r.status >= 200 &&
                    r.status < 300,
            }
        );

        if (!captured) {

            workflowFailed.add(1);
            workflowSuccessRate.add(false);

            return;
        }
    }

    workflowSucceeded.add(1);
    workflowSuccessRate.add(true);
}