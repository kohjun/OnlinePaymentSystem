import pandas as pd
import matplotlib.pyplot as plt
import os
import numpy as np

# --- 1. JTL 파일 경로 정의 (경쟁 테스트 레벨) ---
# NOTE: 이 경로는 사용자님의 JMeter 실행 결과 폴더 구조와 일치해야 합니다.
JTL_FILES_COMPETITIVE = {
    '50': 'load-test/results/stress_test_50t/results.jtl',
    '100': 'load-test/results/stress_test_100t/results.jtl',
    '500': 'load-test/results/stress_test_500t/results.jtl',
    '1000': 'load-test/results/stress_test_1000t/results.jtl',
}

# --- 2. JTL 파일 처리 함수 ---
def process_jtl(test_name, jtl_path):
    try:
        df = pd.read_csv(jtl_path)
        # 'Complete Reservation (Saga Test)' 트랜잭션만 필터링
        df = df[df['label'].str.contains('Complete Reservation', na=False)].copy()

        # 성공적인 응답 (HTTP 200) 필터링
        df_success = df[df['responseCode'].astype(str).str.startswith('200')].copy()

        # 예상된 실패 응답 (HTTP 400 - 재고 부족/결제 실패 등) 필터링
        df_failure = df[df['responseCode'].astype(str).str.startswith('400')].copy()

        # 지연 시간 및 처리량 계산
        latency_95th = df['elapsed'].quantile(0.95) / 1000.0  # ms -> s
        latency_median = df['elapsed'].median() / 1000.0

        total_count = len(df)
        if total_count > 1:
            # 실제 테스트 지속 시간 계산 (JTL 파일의 타임스탬프 기준)
            start_time = df['timeStamp'].min()
            end_time = df['timeStamp'].max()
            duration_s = (end_time - start_time) / 1000.0
        elif total_count == 1:
            duration_s = df['elapsed'].iloc[0] / 1000.0
        else:
            duration_s = 0

        # 총 처리 부하 (TPS)
        throughput = total_count / duration_s if duration_s > 0 else 0

        success_count = len(df_success)
        expected_failure_count = len(df_failure)

        # 동시성 정합성 검증 결과 출력 (재고 3개 가정)
        print(f"--- VALIDATION: {test_name} Threads ---")
        print(f"Total Requests: {total_count}")
        print(f"Successful Orders (200 OK): {success_count}")
        print(f"Expected Failures (400 Bad Request): {expected_failure_count}")
        print(f"Goal Achieved (Success Count <= 3): {success_count <= 3}")
        print("------------------------------------------")


        return {
            'Threads': int(test_name),
            'Success Count': success_count,
            'Total Requests': total_count,
            'Total Handled Load (TPS)': throughput,
            '95th Latency (ms)': latency_95th * 1000,
            'Median Latency (ms)': latency_median * 1000,
            'Duration (s)': duration_s,
        }
    except FileNotFoundError:
        print(f"⚠️ WARNING: File not found - {jtl_path}. Skipping data.")
        return None
    except Exception as e:
        print(f"❌ ERROR occurred while processing {jtl_path}: {e}")
        return None

# --- 3. 성능 시각화 함수 (경쟁 테스트 전용) ---
def visualize_stress_test_competitive_results(jtl_files, output_dir='analysis_output'):
    os.makedirs(output_dir, exist_ok=True)
    results = []

    # JTL 파일 처리
    for name, path in jtl_files.items():
        result = process_jtl(name, path)
        if result:
            results.append(result)

    if not results:
        print("No stress test results found. Please check JTL files.")
        return

    df_results = pd.DataFrame(results).sort_values(by='Threads')
    x_labels = [str(t) for t in df_results['Threads']]

    # --- Plot 1: Concurrency Consistency Proof (정합성) ---
    plt.figure(figsize=(10, 6))
    bars = plt.bar(x_labels, df_results['Success Count'], color='#1f77b4')

    # 예상 성공 횟수 (재고: 3) 기준선
    plt.axhline(y=3, color='r', linestyle='--', linewidth=2, label='Expected Success (Stock: 3)')

    # 바에 값 주석 달기
    for bar in bars:
        yval = bar.get_height()
        plt.text(bar.get_x() + bar.get_width()/2, yval + 0.1, f'{int(yval)}', ha='center', va='bottom', fontsize=11, fontweight='bold')

    plt.title('Concurrency Consistency: Actual Successful Orders vs. Load (Stock=3)', fontsize=15)
    plt.xlabel('Concurrent Users (Threads)', fontsize=12)
    plt.ylabel('Actual Successful Orders (Count)', fontsize=12)
    plt.legend()
    plt.ylim(0, max(df_results['Success Count'].max() + 2, 5))
    plt.grid(axis='y', linestyle='--', alpha=0.5)
    plt.tight_layout()
    output_path_success_count = os.path.join(output_dir, 'concurrency_success_count_competitive.png')
    plt.savefig(output_path_success_count)
    print(f"✅ Concurrency Proof graph generated: {output_path_success_count}")

    # --- Plot 2: Performance Scaling (TPS vs. Latency) (성능) ---
    plt.figure(figsize=(12, 7))

    # 1. TPS (기본 축)
    ax1 = plt.gca()
    ax1.set_xlabel('Concurrent Users (Threads)', fontsize=12)
    ax1.set_ylabel('Total Handled Load (TPS)', color='#1E90FF', fontsize=14)
    bars_tps = ax1.bar(x_labels, df_results['Total Handled Load (TPS)'],
                       color='#1E90FF', alpha=0.7, label='Total Handled Load (TPS)')
    ax1.tick_params(axis='y', labelcolor='#1E90FF')

    # 2. 95th Latency (보조 축)
    ax2 = ax1.twinx()
    ax2.set_ylabel('95th Percentile Latency (ms)', color='#FF4500', fontsize=14)
    line_lat = ax2.plot(x_labels, df_results['95th Latency (ms)'],
                        color='#FF4500', marker='o', linestyle='--', linewidth=3, label='95th Latency (ms)')
    ax2.tick_params(axis='y', labelcolor='#FF4500')

    # TPS 값 주석
    for bar in bars_tps:
        yval = bar.get_height()
        ax1.text(bar.get_x() + bar.get_width()/2, yval + 1, f'{yval:.1f}', ha='center', va='bottom', fontsize=10, fontweight='bold')

    # Latency 값 주석
    for x, y in zip(x_labels, df_results['95th Latency (ms)']):
        ax2.text(x, y + 50, f'{y:.0f}', ha='center', va='bottom', color='#FF4500', fontsize=10)

    # 범례 결합
    lines = list(bars_tps) + list(line_lat)
    labels = [l.get_label() for l in lines]
    ax1.legend(lines, labels, loc='upper left')

    plt.title('Performance Scaling Analysis: TPS vs. Latency (50T to 1000T)', fontsize=16)
    ax1.grid(axis='y', linestyle='--', alpha=0.5)

    plt.tight_layout()
    output_path_scaling = os.path.join(output_dir, 'performance_scaling_competitive.png')
    plt.savefig(output_path_scaling)
    print(f"✅ Performance Scaling graph generated: {output_path_scaling}")

    # 최종 메트릭을 CSV로 저장
    df_results['95th Latency (s)'] = df_results['95th Latency (ms)'] / 1000
    df_results = df_results.drop(columns=['95th Latency (ms)', 'Duration (s)', 'Median Latency (ms)'])
    print("\n--- Final Competitive Metrics CSV ---")
    print(df_results.to_csv(os.path.join(output_dir, 'final_competitive_metrics.csv'), index=False))
    print("-------------------------------------")


if __name__ == '__main__':
    print("================================================")
    print("      🚀 Starting Competitive Performance Analysis Script      ")
    print("================================================")

    JTL_FILES_TO_ANALYZE = {
        '50': 'load-test/results/stress_test_50t/results.jtl',
        '100': 'load-test/results/stress_test_100t/results.jtl',
        '500': 'load-test/results/stress_test_500t/results.jtl',
        '1000': 'load-test/results/stress_test_1000t/results.jtl',
    }

    visualize_stress_test_competitive_results(JTL_FILES_TO_ANALYZE)

    print("\n" + "=" * 70)
    print("All visualization tasks completed. Check the 'analysis_output' folder.")
    print("================================================")