import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = 'http://localhost:8081';

const RATE =
    Number(__ENV.RATE || 5);

const DURATION =
    __ENV.DURATION || '2m';

const unknownPayments =
    new Counter('unknown_payments_created');

const reconciliationSuccessRate =
    new Rate('reconciliation_success_rate');

const reconciliationTime =
    new Trend(
        'reconciliation_time_ms',
        true
    );

export const options = {
    scenarios: {
        reconciliation: {
            executor: 'constant-arrival-rate',

            rate: RATE,

            timeUnit: '1s',

            duration: DURATION,

            preAllocatedVUs: 100,

            maxVUs: 300,
        },
    },

    thresholds: {
        http_req_failed: [
            'rate<0.01',
        ],

        reconciliation_success_rate: [
            'rate>0.99',
        ],

        reconciliation_time_ms: [
            'p(95)<15000',
        ],

        dropped_iterations: [
            'count==0',
        ],
    },
};

export function setup() {

    const response = http.post(
        `${BASE_URL}/processor/mode/TIMEOUT`,
        null,
        {
            tags: {
                name: 'processor_mode_timeout',
            },
        }
    );

    check(response, {
        'processor set to TIMEOUT': (r) =>
            r.status >= 200 &&
            r.status < 300,
    });
}

export default function () {

    const idempotencyKey =
        `reconcile-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        amount:
            (
                Math.floor(
                    Math.random() * 49001
                ) + 100
            ) / 100,

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

        reconciliationSuccessRate.add(false);

        return;
    }

    let payment;

    try {

        payment =
            createResponse.json();

    } catch {

        reconciliationSuccessRate.add(false);

        return;
    }

    const paymentId =
        payment.id;

    const authorizeResponse = http.post(
        `${BASE_URL}/payments/${paymentId}/authorize`,
        null,
        {
            tags: {
                name: 'authorize_payment_timeout',
            },
        }
    );

    const becameUnknown = check(
        authorizeResponse,
        {
            'authorization produces UNKNOWN': (r) => {

                if (
                    r.status < 200 ||
                    r.status >= 300
                ) {
                    return false;
                }

                try {

                    return r.json().status ===
                        'UNKNOWN';

                } catch {

                    return false;
                }
            },
        }
    );

    if (!becameUnknown) {

        reconciliationSuccessRate.add(false);

        return;
    }

    unknownPayments.add(1);

    const start =
        Date.now();

    const deadline =
        start + 30000;

    while (Date.now() < deadline) {

        sleep(0.5);

        const statusResponse = http.get(
            `${BASE_URL}/payments/${paymentId}`,
            {
                tags: {
                    name: 'get_payment_status',
                },
            }
        );

        if (
            statusResponse.status < 200 ||
            statusResponse.status >= 300
        ) {
            continue;
        }

        let currentPayment;

        try {

            currentPayment =
                statusResponse.json();

        } catch {

            continue;
        }

        if (
            currentPayment.status ===
            'AUTHORIZED'
        ) {

            const elapsed =
                Date.now() - start;

            reconciliationTime.add(
                elapsed
            );

            reconciliationSuccessRate.add(
                true
            );

            return;
        }

        if (
            currentPayment.status ===
            'FAILED'
        ) {

            reconciliationSuccessRate.add(
                false
            );

            return;
        }
    }

    reconciliationSuccessRate.add(
        false
    );
}

export function teardown() {

    http.post(
        `${BASE_URL}/processor/mode/SUCCESS`,
        null,
        {
            tags: {
                name: 'processor_mode_success',
            },
        }
    );
}