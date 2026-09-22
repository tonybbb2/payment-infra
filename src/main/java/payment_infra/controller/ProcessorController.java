package payment_infra.controller;

import payment_infra.processor.FakePaymentProcessor;
import payment_infra.processor.ProcessorMode;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/processor")
public class ProcessorController {

    private final FakePaymentProcessor processor;

    public ProcessorController(FakePaymentProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/mode/{mode}")
    public String setMode(@PathVariable ProcessorMode mode) {

        processor.setMode(mode);

        return "Processor mode set to " + mode;
    }
}