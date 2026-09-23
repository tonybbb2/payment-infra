import http from 'k6/http';
import { check } from 'k6';

export const options = {

    stages: [

        {
            duration: '15s',
            target: 10,
        },

        {
            duration: '15s',
            target: 25,
        },

        {
            duration: '15s',
            target: 50,
        },

        {
            duration: '15s',
            target: 100,
        },

        {
            duration: '15s',
            target: 0,
        },
    ],

    thresholds: {

        http_req_failed: [
            'rate<0.01',
        ],
    },
};

export default function () {

    const idempotencyKey =
        `ramp-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        amount: 100.00,
        currency: 'CAD',
    });

    const response = http.post(

        'http://localhost:8081/payments',

        payload,

        {
            headers: {

                'Content-Type':
                    'application/json',

                'Idempotency-Key':
                    idempotencyKey,
            },
        }
    );

    check(response, {

        'request succeeded': (r) =>
            r.status === 200 ||
            r.status === 201,
    });
}