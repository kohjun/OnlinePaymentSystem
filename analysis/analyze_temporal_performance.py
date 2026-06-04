import os
import sys

import pandas as pd


def analyze_temporal_performance(jtl_path, output_dir="temporal_performance_output"):
    if not os.path.exists(jtl_path):
        print(f"Error: file not found - {jtl_path}")
        return

    try:
        try:
            df = pd.read_csv(jtl_path, encoding="utf-8")
        except UnicodeDecodeError:
            df = pd.read_csv(jtl_path, encoding="cp949")

        df = df[df["label"].str.contains("Complete Reservation", na=False)].copy()
        if df.empty:
            print("Error: no Complete Reservation samples found.")
            return

        df["responseCode"] = df["responseCode"].astype(str)
        total = len(df)
        completed = len(df[df["responseCode"].str.startswith("200")])
        pending = len(df[df["responseCode"].str.startswith("202")])
        failed = len(df[~df["responseCode"].str.match(r"^(200|202)")])

        duration_s = max((df["timeStamp"].max() - df["timeStamp"].min()) / 1000.0, 1.0)
        throughput = total / duration_s

        metrics = pd.DataFrame([{
            "total_requests": total,
            "completed_200": completed,
            "pending_202": pending,
            "failed_or_rejected": failed,
            "accepted_rate_percent": ((completed + pending) / total) * 100,
            "workflow_duration_avg_ms": df["elapsed"].mean(),
            "workflow_duration_p95_ms": df["elapsed"].quantile(0.95),
            "throughput_tps": throughput,
        }])

        os.makedirs(output_dir, exist_ok=True)
        output_path = os.path.join(output_dir, "temporal_metrics.csv")
        metrics.to_csv(output_path, index=False)

        print("--- Temporal Performance Summary ---")
        print(metrics.to_string(index=False))
        print()
        print("Inventory consistency check:")
        print("- For stock-limited tests, verify completed_200 does not exceed the seeded stock.")
        print("- 202 PENDING responses must be checked through GET /api/reservations/workflows/{workflowId}.")
        print()
        print("Operational metrics to inspect in DB/Temporal UI:")
        print("- activity retry count")
        print("- payment compensation count")
        print("- outbox publish latency and FAILED events")
        print(f"CSV written: {output_path}")

    except Exception as exc:
        print(f"Error analyzing Temporal performance: {exc}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python analysis/analyze_temporal_performance.py <jtl_file_path>")
        sys.exit(1)

    analyze_temporal_performance(sys.argv[1])
