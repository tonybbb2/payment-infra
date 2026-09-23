import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 50,
    iterations: 500,

    thresholds: {
        http_req_failed: [
            'rate<0.01',
        ],

        http_req_duration: [
            'p(95)<500',
        ],
    },
};

export function setup() {

    const idempotencyKey =
        `shared-idempotency-${Date.now()}`;

    const payload = JSON.stringify({
        amount: 250.00,
        currency: 'CAD',
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
    };

    const response = http.post(
        'http://localhost:8081/payments',
        payload,
        params
    );

    check(response, {
        'setup request succeeds': (r) =>
            r.status === 200 ||
            r.status === 201,
    });

    const payment =
        response.json();

    return {
        idempotencyKey:
            idempotencyKey,

        expectedPaymentId:
            payment.id,
    };
}

export default function (data) {

    const payload = JSON.stringify({
        amount: 250.00,
        currency: 'CAD',
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',

            'Idempotency-Key':
                data.idempotencyKey,
        },
    };

    const response = http.post(
        'http://localhost:8081/payments',
        payload,
        params
    );

    check(response, {

        'request succeeds': (r) =>
            r.status === 200 ||
            r.status === 201,

        'same payment ID returned': (r) => {

            try {

                return r.json().id ===
                    data.expectedPaymentId;

            } catch {
                return false;
            }
        },
    });
}