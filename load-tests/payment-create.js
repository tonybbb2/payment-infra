import http from 'k6/http';
import { check } from 'k6';

export const options = {

    vus: 20,

    duration: '30s',

    thresholds: {

        http_req_failed: [
            'rate<0.01',
        ],

        http_req_duration: [
            'p(95)<500',
        ],
    },
};

export default function () {

    const idempotencyKey =
        `load-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        amount: 100.00,
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

        'status is 200 or 201': (r) =>
            r.status === 200 ||
            r.status === 201,
    });
}