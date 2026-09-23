import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const paymentSucceeded = new Counter('payment_workflows_succeeded');
const paymentFailed = new Counter('payment_workflows_failed');
const idempotentRetries = new Counter('idempotent_retries');
const workflowSuccessRate = new Rate('workflow_success_rate');

const BASE_URL = 'http://localhost:8081';

export const options = {
    scenarios: {
        payments: {
            executor: 'constant-arrival-rate',
            rate: 50,
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 50,
            maxVUs: 200,
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

    const random = Math.random();

    const idempotencyKey =
        `workload-${__VU}-${__ITER}-${Date.now()}`;

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

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },

        tags: {
            name: 'create_payment',
        },
    };

    const createResponse = http.post(
        `${BASE_URL}/payments`,
        payload,
        params
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

        paymentFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    let payment;

    try {

        payment =
            createResponse.json();

    } catch {

        paymentFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    const paymentId =
        payment.id;

    if (random < 0.02) {

        const retryResponse = http.post(
            `${BASE_URL}/payments`,
            payload,
            params
        );

        const retrySucceeded = check(
            retryResponse,
            {
                'idempotent retry succeeds': (r) =>
                    r.status === 200 ||
                    r.status === 201,

                'retry returns same payment': (r) => {

                    try {

                        return r.json().id ===
                            paymentId;

                    } catch {

                        return false;
                    }
                },
            }
        );

        if (!retrySucceeded) {

            paymentFailed.add(1);
            workflowSuccessRate.add(false);

            return;
        }

        idempotentRetries.add(1);
    }

    sleep(
        Math.random() * 0.25
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

        paymentFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    if (random < 0.70) {

        paymentSucceeded.add(1);
        workflowSuccessRate.add(true);

        sleep(
            Math.random() * 0.5
        );

        return;
    }

    sleep(
        0.1 +
        Math.random() * 0.4
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

        paymentFailed.add(1);
        workflowSuccessRate.add(false);

        return;
    }

    if (random >= 0.95) {

        sleep(
            0.2 +
            Math.random() * 0.8
        );

        const refundResponse = http.post(
            `${BASE_URL}/payments/${paymentId}/refund`,
            null,
            {
                tags: {
                    name: 'refund_payment',
                },
            }
        );

        const refunded = check(
            refundResponse,
            {
                'refund succeeds': (r) =>
                    r.status >= 200 &&
                    r.status < 300,
            }
        );

        if (!refunded) {

            paymentFailed.add(1);
            workflowSuccessRate.add(false);

            return;
        }
    }

    paymentSucceeded.add(1);
    workflowSuccessRate.add(true);

    sleep(
        Math.random() * 0.5
    );
}