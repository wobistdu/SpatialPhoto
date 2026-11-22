"""Build an isolated weight-only SHARP candidate; never replace shipped assets.

Unlike dynamic INT8, this keeps activations in floating point. Every candidate
still needs same-image geometry/render comparisons and Android CPU validation.
Run with the project's ONNX Python environment.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import time

import onnx
from onnxruntime.quantization.matmul_nbits_quantizer import (
    DefaultWeightOnlyQuantConfig,
    MatMulNBitsQuantizer,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bits", type=int, choices=(4, 8), default=8)
    parser.add_argument("--block-size", type=int, default=32)
    args = parser.parse_args()
    source = args.input.resolve()
    output = args.output.resolve()
    if source.parent == output.parent or output.exists():
        parser.error("Use a new output file outside the shipped model directory.")
    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    config = DefaultWeightOnlyQuantConfig(
        bits=args.bits,
        block_size=args.block_size,
        is_symmetric=True,
        # Keep activation arithmetic floating point; no dynamic INT8 fallback.
        accuracy_level=1,
        op_types_to_quantize=("MatMul",),
    )
    quantizer = MatMulNBitsQuantizer(str(source), algo_config=config)
    quantizer.process()
    quantizer.model.save_model_to_file(str(output), use_external_data_format=True)
    model = onnx.load(str(output), load_external_data=False)
    report = {
        "source": str(source),
        "output": str(output),
        "bits": args.bits,
        "blockSize": args.block_size,
        "activationQuantization": False,
        "productionApproved": False,
        "seconds": time.monotonic() - started,
        "ops": dict(Counter(node.op_type for node in model.graph.node)),
        "files": {p.name: p.stat().st_size for p in output.parent.glob(output.name + "*")},
    }
    output.with_suffix(".report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
