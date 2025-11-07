import pandas as pd
import matplotlib.pyplot as plt
import os
import numpy as np

# --- 1. JTL 파일 경로 정의 ---
# NOTE: To run this script, please make sure the JTL files exist at these paths.
JTL_FILES = {
    # Scenario 1 (Competition Proof) results: Stock 3, Lock 1s
    'Competitive_10': 'load-test/results/stress_test_10t/results.jtl',
    'Competitive_100': 'load-test/results/stress_test_100t/results.jtl',
    'Competitive_1000': 'load-test/results/stress_test_1000t/results.jtl',

    # Scenario 2 (Max Throughput) result: Lock Disabled, Stock 10000+
    'MaxTPS_1000': 'load-test/results/max_throughput_1000t/results.jtl',
}

# --- 2. JTL 파일 처리 함수 ---
def process_jtl(test_name, jtl_path):
    try:
        df = pd.read_csv(jtl_path)
        # Filter for relevant transaction labels (assuming 'Complete Reservation (Saga Test)')
        df = df[df['label'].str.contains('Complete Reservation', na=False)].copy()

        # Filter for successful responses (HTTP 200)
        df_success = df[df['responseCode'].astype(str).str.startswith('200')].copy()

        # Calculate metrics
        latency_95th = df['elapsed'].quantile(0.95) / 1000.0  # ms -> s

        if len(df) > 1:
            total_time_s = (df['timeStamp'].max() - df['timeStamp'].min()) / 1000.0
        elif len(df) == 1:
            total_time_s = df['elapsed'].iloc[0] / 1000.0
        else:
            total_time_s = 0

        throughput = len(df) / total_time_s if total_time_s > 0 else 0

        success_count = len(df_success)
        total_count = len(df)
        success_rate = success_count / total_count * 100 if total_count > 0 else 0

        # Log the critical concurrency validation point (for Competitive Scenarios)
        if 'Competitive' in test_name:
            print(f"--- VALIDATION: {test_name} (Stock=3) ---")
            print(f"Total Requests: {total_count}")
            print(f"Successful Orders (200 OK): {success_count}")
            print(f"Goal Achieved (Success Count <= 3): {success_count <= 3}")
            print("------------------------------------------")


        return {
            'Scenario': test_name,
            'Success Count': success_count,
            'Success Rate (%)': success_rate,
            '95th Latency (s)': latency_95th,
            'Overall Throughput (TPS)': throughput
        }
    except FileNotFoundError:
        print(f"⚠️ WARNING: File not found - {jtl_path}. Skipping data.")
        return None
    except Exception as e:
        print(f"❌ ERROR occurred while processing {jtl_path}: {e}")
        return None

# --- 3. 성능 시각화 함수 ---
def visualize_stress_test_results(jtl_files, output_dir='analysis_output'):
    os.makedirs(output_dir, exist_ok=True)
    results = []

    # Process all JTL files
    for name, path in jtl_files.items():
        result = process_jtl(name, path)
        if result:
            results.append(result)

    if not results:
        print("No stress test results found. Please check JTL files.")
        return

    df_results = pd.DataFrame(results)

    # --- Plotting Success Count (Concurrency Proof) ---
    competitive_data = df_results[df_results['Scenario'].str.contains('Competitive')]

    if not competitive_data.empty:
        plt.figure(figsize=(10, 6))

        # Prepare x-axis labels: Extract threads from scenario name
        x_labels = [s.split('_')[1] for s in competitive_data['Scenario']]

        bars = plt.bar(x_labels, competitive_data['Success Count'],
                       color=['#1f77b4', '#ff7f0e', '#2ca02c'])

        # Expected Success (Stock: 3) reference line
        plt.axhline(y=3, color='r', linestyle='--', linewidth=2, label='Expected Success (Stock: 3)')

        # Annotate bars
        for bar in bars:
            yval = bar.get_height()
            plt.text(bar.get_x() + bar.get_width()/2, yval + 0.1, f'{int(yval)}', ha='center', va='bottom', fontsize=11, fontweight='bold')

        plt.title('Concurrency Consistency: Actual Successful Orders (Stock=3)', fontsize=15)
        plt.xlabel('Concurrent Users (Threads)', fontsize=12)
        plt.ylabel('Actual Successful Orders (Count)', fontsize=12)
        plt.legend()
        plt.ylim(0, max(competitive_data['Success Count'].max() + 2, 5))
        plt.grid(axis='y', linestyle='--', alpha=0.5)
        plt.tight_layout()
        output_path_success_count = os.path.join(output_dir, 'concurrency_success_count_proof.png')
        plt.savefig(output_path_success_count)
        print(f"✅ Concurrency Proof graph generated: {output_path_success_count}")


    # --- Plotting Key Performance Indicators (TPS and Latency) ---
    comparison_data = df_results[['Scenario', 'Overall Throughput (TPS)', '95th Latency (s)']].copy()

    # Rename scenarios for clearer visualization labels
    comparison_data['Scenario'] = comparison_data['Scenario'].replace({
        'Competitive_100': 'Competitive Lock (Stock 3)',
        'MaxTPS_1000': 'Max Throughput (Lock Disabled)'
    })

    plt.figure(figsize=(12, 7))

    # 1. TPS (Primary Focus for comparison)
    ax1 = plt.gca()
    ax1.set_xlabel('Testing Scenario', fontsize=12)
    ax1.set_ylabel('Throughput (TPS)', color='#1E90FF', fontsize=14)
    bars_tps = ax1.bar(comparison_data['Scenario'], comparison_data['Overall Throughput (TPS)'],
                       color=['#1E90FF', '#00BFFF'], alpha=0.7, label='Overall Throughput (TPS)')
    ax1.tick_params(axis='y', labelcolor='#1E90FF')

    # 2. Latency (Secondary Axis)
    ax2 = ax1.twinx()
    ax2.set_ylabel('95th Percentile Latency (s)', color='#FF4500', fontsize=14)
    line_lat = ax2.plot(comparison_data['Scenario'], comparison_data['95th Latency (s)'],
                        color='#FF4500', marker='o', linestyle='--', linewidth=3, label='95th Latency')
    ax2.tick_params(axis='y', labelcolor='#FF4500')

    # Annotate bars with TPS values
    for bar in bars_tps:
        yval = bar.get_height()
        ax1.text(bar.get_x() + bar.get_width()/2, yval + 5, f'{yval:.1f} TPS', ha='center', va='bottom', fontsize=10, fontweight='bold')

    # Annotate latency points
    for x, y in zip(comparison_data['Scenario'], comparison_data['95th Latency (s)']):
        ax2.text(x, y + 0.5, f'{y:.2f} s', ha='center', va='bottom', color='#FF4500', fontsize=10)

    # Combine legends
    lines = bars_tps + line_lat
    labels = [l.get_label() for l in lines]
    ax1.legend(lines, labels, loc='upper left')

    plt.title('Performance Comparison: Competitive vs. Max Throughput', fontsize=16)
    ax1.grid(axis='y', linestyle='--', alpha=0.5)

    plt.tight_layout()
    output_path = os.path.join(output_dir, 'final_performance_comparison.png')
    plt.savefig(output_path)
    print(f"✅ Final Performance Comparison graph generated: {output_path}")


    # Save final metrics to CSV
    df_results['95th Latency (ms)'] = df_results['95th Latency (s)'] * 1000
    df_results = df_results.drop(columns=['95th Latency (s)'])
    print("\n--- Final Metrics CSV ---")
    print(df_results.to_csv(os.path.join(output_dir, 'final_stress_test_metrics.csv'), index=False))
    print("-------------------------")


if __name__ == '__main__':
    print("================================================")
    print("      🚀 Starting Performance Analysis Script      ")
    print("================================================")

    # Note: To fully execute all scenarios, run the JMeter commands below first.
    # We focus on the comparison between Competitive Lock (100 threads) and Max Throughput (1000 threads)

    JTL_FILES_TO_RUN = {
        # 1. Competitive Scenario (Stock 3, Lock 1s) - Use 100 threads for comparison
        'Competitive_100': 'load-test/results/stress_test_100t/results.jtl',

        # 2. Max Throughput Scenario (Lock Disabled, Stock 10000) - Use 1000 threads for max load
        'MaxTPS_1000': 'load-test/results/max_throughput_1000t/results.jtl',
    }

    visualize_stress_test_results(JTL_FILES_TO_RUN)

    print("\n" + "=" * 70)
    print("All visualization tasks completed. Check the 'analysis_output' folder.")
    print("================================================")