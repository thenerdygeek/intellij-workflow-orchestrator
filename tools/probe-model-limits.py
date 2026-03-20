#!/usr/bin/env python3
"""
Probe Sourcegraph LLM API to discover actual token limits per model.

Uses binary search to find:
1. Max input context tokens (where context_length_exceeded fires)
2. Max output tokens (where the API caps or errors)

Usage:
    python tools/probe-model-limits.py --url https://sourcegraph.example.com --token sgp_xxx
    python tools/probe-model-limits.py --url https://sourcegraph.example.com --token sgp_xxx --model "anthropic::2024-10-22::claude-opus-4-20250514"
    python tools/probe-model-limits.py --url https://sourcegraph.example.com --token sgp_xxx --list-models
"""

import argparse
import json
import sys
import time
import requests
from typing import Optional, Tuple


def list_models(base_url: str, token: str) -> list:
    """Fetch available models from Sourcegraph."""
    url = f"{base_url}/.api/llm/models"
    headers = {"Authorization": f"token {token}"}

    resp = requests.get(url, headers=headers, timeout=30)
    if resp.status_code != 200:
        print(f"Error listing models: {resp.status_code} {resp.text[:200]}")
        return []

    data = resp.json()
    models = data.get("data", [])
    return models


def format_model_name(model_id: str) -> str:
    """Format raw model ID into readable name."""
    parts = model_id.split("::")
    if len(parts) >= 3:
        provider = parts[0].capitalize()
        name = parts[2].replace("-", " ").title()
        return f"{name} ({provider})"
    return model_id


def generate_filler_text(approx_tokens: int) -> str:
    """Generate text that's approximately the given number of tokens.

    Uses random English words to avoid tokenizer compression of repeated text.
    BPE tokenizers compress repeated patterns aggressively, so "the quick brown fox"
    repeated 1000x would be far fewer tokens than expected. Random words give
    accurate token counts (~1.3 tokens per word for common English).

    Rough heuristic: 1 token ≈ 0.75 words (or ~1.3 tokens per word).
    """
    import random

    # Common English words — diverse vocabulary to prevent tokenizer compression
    words = [
        "abstract", "balance", "capture", "danger", "element", "fabric", "garden",
        "harbor", "impact", "jungle", "kernel", "ladder", "magnet", "nature",
        "orange", "palace", "quarter", "random", "sample", "target", "unique",
        "valley", "winter", "yellow", "zebra", "account", "bridge", "castle",
        "desert", "engine", "forest", "guitar", "helmet", "island", "jacket",
        "kitten", "lemon", "marble", "needle", "ocean", "pencil", "rabbit",
        "silver", "tower", "umbrella", "violet", "wallet", "anchor", "basket",
        "carbon", "dragon", "empire", "falcon", "global", "hidden", "insect",
        "jovial", "knight", "lantern", "mirror", "narrow", "orbital", "planet",
        "quartz", "rocket", "spiral", "timber", "urgent", "velvet", "whisper",
        "crystal", "blanket", "chapter", "diamond", "feather", "glacier", "horizon",
        "journey", "kitchen", "library", "message", "network", "pattern", "quality",
        "shelter", "temple", "venture", "weather", "cabinet", "dolphin", "elegant",
        "fiction", "gateway", "harvest", "initial", "justice", "kingdom", "mineral",
        "notable", "organic", "physics", "replica", "science", "thunder", "village",
        "central", "digital", "express", "fortune", "genuine", "hundred", "imagine",
        "logical", "monitor", "neutral", "observe", "premium", "resolve", "special",
        "therapy", "variety", "capable", "distant", "episode", "fragile", "graphic",
        "intense", "literal", "mystery", "outline", "primary", "require", "surface",
        "trouble", "visible", "working", "complex", "dynamic", "fashion", "general",
        "hunting", "instant", "leather", "missing", "nothing", "opinion", "passage",
        "reading", "several", "trading", "typical", "virtual", "welfare", "battery",
        "channel", "display", "embrace", "formula", "dealing", "healthy", "involve",
        "leading", "manager", "obvious", "present", "reality", "strange", "turning",
    ]

    # ~1.3 tokens per word on average for BPE tokenizers
    num_words = int(approx_tokens / 1.3)
    random.seed(42 + approx_tokens)  # Deterministic per size for reproducibility

    # Generate sentences of 8-15 words for natural-looking text
    result = []
    i = 0
    while i < num_words:
        sentence_len = random.randint(8, 15)
        sentence_words = [random.choice(words) for _ in range(min(sentence_len, num_words - i))]
        sentence_words[0] = sentence_words[0].capitalize()
        result.append(" ".join(sentence_words) + ".")
        i += sentence_len

    return " ".join(result)


def call_api(
    base_url: str,
    token: str,
    model: str,
    messages: list,
    max_tokens: int = 100,
    timeout: int = 60
) -> Tuple[bool, str, Optional[dict]]:
    """
    Call the Sourcegraph chat completions API.

    Returns: (success, error_message_or_content, full_response)
    """
    url = f"{base_url}/.api/llm/chat/completions"
    headers = {
        "Authorization": f"token {token}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "stream": False
    }

    try:
        resp = requests.post(url, headers=headers, json=payload, timeout=timeout)

        if resp.status_code == 200:
            data = resp.json()
            content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
            usage = data.get("usage", {})
            return True, content, {"usage": usage, "status": 200}
        else:
            error_text = resp.text[:500]
            return False, error_text, {"status": resp.status_code, "body": error_text}

    except requests.exceptions.Timeout:
        return False, "TIMEOUT", {"status": "timeout"}
    except Exception as e:
        return False, str(e), {"status": "error", "exception": str(e)}


def is_context_length_error(error_msg: str) -> bool:
    """Check if the error is specifically about context length."""
    lower = error_msg.lower()
    return any(phrase in lower for phrase in [
        "context_length",
        "context length",
        "maximum context",
        "token limit",
        "too many tokens",
        "exceeds the maximum",
        "input too long",
        "prompt is too long"
    ])


def probe_input_limit(
    base_url: str,
    token: str,
    model: str,
    low: int = 1000,
    high: int = 300000,
    verbose: bool = True
) -> int:
    """
    Binary search for the maximum input context size.

    Sends messages with increasing token counts until context_length_exceeded.
    Returns the approximate max input tokens.
    """
    print(f"\n{'='*60}")
    print(f"PROBING INPUT LIMIT for {format_model_name(model)}")
    print(f"{'='*60}")
    print(f"Search range: {low:,} - {high:,} tokens")
    print()

    # First, verify the model works at all with a minimal request
    print("Step 0: Verifying model responds...")
    success, msg, resp = call_api(
        base_url, token, model,
        [{"role": "user", "content": "Say hello."}],
        max_tokens=50
    )
    if not success:
        print(f"  ✗ Model doesn't respond: {msg[:200]}")
        return 0

    usage = resp.get("usage", {})
    print(f"  ✓ Model responds. Usage: prompt={usage.get('prompt_tokens', '?')}, "
          f"completion={usage.get('completion_tokens', '?')}")
    print()

    # Binary search
    last_success = low
    iteration = 0

    while high - low > 1000:  # Stop when range is < 1000 tokens
        iteration += 1
        mid = (low + high) // 2

        filler = generate_filler_text(mid)
        messages = [
            {"role": "user", "content": f"Please reply with just 'OK'. Here is some context:\n{filler}"}
        ]

        if verbose:
            print(f"  Iteration {iteration}: trying ~{mid:,} tokens... ", end="", flush=True)

        success, msg, resp = call_api(base_url, token, model, messages, max_tokens=50, timeout=120)

        if success:
            usage = resp.get("usage", {})
            actual_prompt = usage.get("prompt_tokens", mid)
            if verbose:
                print(f"✓ (actual prompt tokens: {actual_prompt:,})")
            low = mid
            last_success = actual_prompt if actual_prompt > 0 else mid
        else:
            if is_context_length_error(msg):
                if verbose:
                    print(f"✗ context_length_exceeded")
                high = mid
            else:
                # Other error — might be rate limit, server error, etc.
                if verbose:
                    print(f"? other error: {msg[:100]}")
                # Check if it's a rate limit
                if "429" in msg or "rate" in msg.lower():
                    if verbose:
                        print("    Rate limited, waiting 5s...")
                    time.sleep(5)
                    continue  # Retry same value
                else:
                    # Treat as upper bound (conservative)
                    high = mid

        # Small delay to avoid rate limits
        time.sleep(1)

    print(f"\n{'─'*60}")
    print(f"RESULT: Max input ≈ {last_success:,} tokens")
    print(f"  (Binary search converged at range {low:,} - {high:,})")
    print(f"{'─'*60}")

    return last_success


def probe_output_limit(
    base_url: str,
    token: str,
    model: str,
    verbose: bool = True
) -> int:
    """
    Probe the maximum output tokens the API allows.

    Strategy: Set max_tokens to increasing values and check:
    - Does the API accept the value?
    - Does the actual completion use that many tokens?
    """
    print(f"\n{'='*60}")
    print(f"PROBING OUTPUT LIMIT for {format_model_name(model)}")
    print(f"{'='*60}")

    test_values = [100, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000]
    last_accepted = 0

    for max_tokens in test_values:
        if verbose:
            print(f"  max_tokens={max_tokens:,}... ", end="", flush=True)

        # Ask for a long response
        messages = [
            {"role": "user", "content": f"Count from 1 to 10000, one number per line. Do not stop until you reach 10000 or run out of tokens. Start now: 1"}
        ]

        success, content, resp = call_api(
            base_url, token, model, messages,
            max_tokens=max_tokens, timeout=120
        )

        if success:
            usage = resp.get("usage", {})
            actual_completion = usage.get("completion_tokens", 0)
            if verbose:
                print(f"✓ (used {actual_completion:,} completion tokens)")
            last_accepted = max_tokens

            # If actual completion is much less than max_tokens, the model
            # naturally stopped. Try to distinguish between:
            # a) Model finished the task (doesn't need more tokens)
            # b) API capped the output (actual == max_tokens or close)
            if actual_completion > 0 and actual_completion < max_tokens * 0.8:
                if verbose:
                    print(f"    → Model used only {actual_completion:,}/{max_tokens:,} "
                          f"({actual_completion*100//max_tokens}%). Might be task-complete, not capped.")
        else:
            if verbose:
                print(f"✗ error: {resp.get('body', '')[:100]}")
            # If the API rejects this max_tokens value, the previous one was the limit
            break

        time.sleep(1)

    # Now binary search between last_accepted and the failed value
    if last_accepted < test_values[-1]:
        # Find exact boundary
        low = last_accepted
        high = test_values[test_values.index(last_accepted) + 1] if last_accepted in test_values else last_accepted * 2

        while high - low > 100:
            mid = (low + high) // 2
            if verbose:
                print(f"  Binary search: max_tokens={mid:,}... ", end="", flush=True)

            messages = [{"role": "user", "content": "Count from 1 to 10000."}]
            success, _, resp = call_api(base_url, token, model, messages, max_tokens=mid, timeout=60)

            if success:
                if verbose:
                    print("✓")
                low = mid
            else:
                if verbose:
                    print("✗")
                high = mid

            time.sleep(1)

        last_accepted = low

    print(f"\n{'─'*60}")
    print(f"RESULT: Max output ≈ {last_accepted:,} tokens")
    print(f"{'─'*60}")

    return last_accepted


def main():
    parser = argparse.ArgumentParser(
        description="Probe Sourcegraph LLM API token limits per model",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # List available models
  python tools/probe-model-limits.py --url https://sg.example.com --token sgp_xxx --list-models

  # Probe specific model
  python tools/probe-model-limits.py --url https://sg.example.com --token sgp_xxx \\
      --model "anthropic::2024-10-22::claude-opus-4-20250514"

  # Probe all Claude models
  python tools/probe-model-limits.py --url https://sg.example.com --token sgp_xxx --probe-all

  # Only probe output limit
  python tools/probe-model-limits.py --url https://sg.example.com --token sgp_xxx \\
      --model "anthropic::2024-10-22::claude-sonnet-4-20250514" --output-only
        """
    )
    parser.add_argument("--url", required=True, help="Sourcegraph instance URL")
    parser.add_argument("--token", required=True, help="Sourcegraph access token")
    parser.add_argument("--model", help="Specific model ID to probe")
    parser.add_argument("--list-models", action="store_true", help="List available models and exit")
    parser.add_argument("--probe-all", action="store_true", help="Probe all Anthropic models")
    parser.add_argument("--input-only", action="store_true", help="Only probe input limit")
    parser.add_argument("--output-only", action="store_true", help="Only probe output limit")
    parser.add_argument("--input-low", type=int, default=1000, help="Input search lower bound (default: 1000)")
    parser.add_argument("--input-high", type=int, default=300000, help="Input search upper bound (default: 300000)")
    parser.add_argument("--quiet", action="store_true", help="Less verbose output")

    args = parser.parse_args()
    base_url = args.url.rstrip("/")
    verbose = not args.quiet

    # List models
    print(f"Connecting to {base_url}...")
    models = list_models(base_url, args.token)

    if not models:
        print("No models found or connection failed.")
        sys.exit(1)

    if args.list_models:
        print(f"\nAvailable models ({len(models)}):\n")

        # Group by provider
        by_provider = {}
        for m in models:
            mid = m.get("id", "")
            provider = mid.split("::")[0] if "::" in mid else "unknown"
            by_provider.setdefault(provider, []).append(mid)

        for provider in sorted(by_provider.keys()):
            print(f"  {provider.upper()}")
            for mid in sorted(by_provider[provider]):
                print(f"    {mid}")
                print(f"      → {format_model_name(mid)}")
            print()

        sys.exit(0)

    # Determine which models to probe
    models_to_probe = []

    if args.model:
        models_to_probe = [args.model]
    elif args.probe_all:
        models_to_probe = [
            m["id"] for m in models
            if m.get("id", "").startswith("anthropic::")
        ]
    else:
        # Default: probe latest opus + sonnet
        model_ids = [m.get("id", "") for m in models]

        opus = [m for m in model_ids if "opus" in m.lower()]
        sonnet = [m for m in model_ids if "sonnet" in m.lower()]

        if opus:
            models_to_probe.append(sorted(opus)[-1])  # Latest opus
        if sonnet:
            models_to_probe.append(sorted(sonnet)[-1])  # Latest sonnet

        if not models_to_probe:
            print("No Anthropic models found. Use --model to specify one.")
            sys.exit(1)

    print(f"\nModels to probe: {len(models_to_probe)}")
    for m in models_to_probe:
        print(f"  • {format_model_name(m)}")

    # Probe each model
    results = {}

    for model_id in models_to_probe:
        result = {"model": model_id, "display_name": format_model_name(model_id)}

        if not args.output_only:
            result["max_input"] = probe_input_limit(
                base_url, args.token, model_id,
                low=args.input_low, high=args.input_high,
                verbose=verbose
            )

        if not args.input_only:
            result["max_output"] = probe_output_limit(
                base_url, args.token, model_id,
                verbose=verbose
            )

        results[model_id] = result

    # Summary
    print(f"\n{'='*60}")
    print("SUMMARY")
    print(f"{'='*60}\n")

    for model_id, result in results.items():
        print(f"Model: {result['display_name']}")
        print(f"  ID: {model_id}")
        if "max_input" in result:
            print(f"  Max Input:  {result['max_input']:>10,} tokens")
        if "max_output" in result:
            print(f"  Max Output: {result['max_output']:>10,} tokens")
        print()

    # Save results
    results_file = "tools/model-limits-results.json"
    with open(results_file, "w") as f:
        json.dump(results, f, indent=2)
    print(f"Results saved to {results_file}")


if __name__ == "__main__":
    main()
