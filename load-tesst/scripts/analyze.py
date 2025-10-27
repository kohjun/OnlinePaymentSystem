#!/usr/bin/env python3
"""
JMeter 결과 분석 스크립트
Payment Service 성능 테스트 결과를 상세 분석합니다.
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib
matplotlib.use('Agg')  # GUI 없이 실행 가능
import sys
from pathlib import Path
from datetime import datetime

# 한글 폰트 설정 (선택사항)
plt.rcParams['font.family'] = 'DejaVu Sans'
plt.rcParams['axes.unicode_minus'] = False

class JMeterAnalyzer:
    """JMeter 결과 분석기"""

    def __init__(self, jtl_path):
        self.jtl_path = Path(jtl_path)
        self.df = None
        self.output_dir = self.jtl_path.parent

    def load_data(self):
        """JTL 파일 로드"""
        print(f"\n📂 파일 로드 중: {self.jtl_path}")

        try:
            self.df = pd.read_csv(self.jtl_path)
            print(f"✅ {len(self.df)} 개의 요청 로드 완료")

            # 타임스탬프를 datetime으로 변환
            self.df['timeStamp'] = pd.to_datetime(self.df['timeStamp'], unit='ms')

            return True
        except Exception as e:
            print(f"❌ 파일 로드 실패: {e}")
            return False

    def print_summary(self):
        """기본 통계 출력"""
        print("\n" + "="*70)
        print("📊 응답 시간 통계 (Response Time Statistics)")
        print("="*70)

        stats = self.df['elapsed'].describe()
        print(f"총 요청 수:        {len(self.df):,}")
        print(f"평균 (Mean):       {stats['mean']:.2f} ms")
        print(f"중앙값 (Median):    {self.df['elapsed'].median():.2f} ms")
        print(f"최소값 (Min):      {stats['min']:.2f} ms")
        print(f"최대값 (Max):      {stats['max']:.2f} ms")
        print(f"표준편차 (Std):    {stats['std']:.2f} ms")
        print(f"\n백분위수 (Percentiles):")
        print(f"  P50 (Median):    {self.df['elapsed'].quantile(0.50):.2f} ms")
        print(f"  P90:             {self.df['elapsed'].quantile(0.90):.2f} ms")
        print(f"  P95:             {self.df['elapsed'].quantile(0.95):.2f} ms")
        print(f"  P99:             {self.df['elapsed'].quantile(0.99):.2f} ms")

    def analyze_tps(self):
        """TPS 분석"""
        print("\n" + "="*70)
        print("⚡ TPS (Transactions Per Second)")
        print("="*70)

        tps = self.df.set_index('timeStamp').resample('1S').size()

        print(f"평균 TPS:          {tps.mean():.2f}")
        print(f"최대 TPS:          {tps.max():.2f}")
        print(f"최소 TPS:          {tps.min():.2f}")
        print(f"중앙값 TPS:        {tps.median():.2f}")

        return tps

    def analyze_success_rate(self):
        """성공률 분석"""
        print("\n" + "="*70)
        print("✅ 성공률 (Success Rate)")
        print("="*70)

        total = len(self.df)
        success = self.df['success'].sum()
        failed = total - success
        success_rate = (success / total) * 100

        print(f"전체 요청:         {total:,}")
        print(f"성공:              {success:,}")
        print(f"실패:              {failed:,}")
        print(f"성공률:            {success_rate:.2f}%")

        if failed > 0:
            print(f"\n⚠️  실패한 요청이 있습니다!")
            error_codes = self.df[self.df['success'] == False]['responseCode'].value_counts()
            print("\n에러 코드 분포:")
            for code, count in error_codes.items():
                print(f"  {code}: {count}회")

    def analyze_by_endpoint(self):
        """엔드포인트별 성능 분석"""
        print("\n" + "="*70)
        print("🎯 엔드포인트별 성능 (Performance by Endpoint)")
        print("="*70)

        grouped = self.df.groupby('label').agg({
            'elapsed': ['count', 'mean', 'median', 'min', 'max',
                       lambda x: x.quantile(0.95)],
            'success': 'sum'
        }).round(2)

        # 컬럼명 정리
        grouped.columns = ['Count', 'Mean(ms)', 'Median(ms)', 'Min(ms)',
                          'Max(ms)', 'P95(ms)', 'Success']
        grouped['Success Rate (%)'] = (grouped['Success'] / grouped['Count'] * 100).round(2)

        print(grouped.to_string())

    def detect_bottlenecks(self):
        """병목 구간 탐지"""
        print("\n" + "="*70)
        print("🔥 병목 구간 탐지 (Bottleneck Detection)")
        print("="*70)

        # 시간대별 평균 응답 시간
        response_time_series = self.df.set_index('timeStamp')['elapsed'].resample('5S').mean()

        # 평균의 2배 이상인 구간을 병목으로 간주
        threshold = response_time_series.mean() * 2
        bottlenecks = response_time_series[response_time_series > threshold]

        if len(bottlenecks) > 0:
            print(f"⚠️  {len(bottlenecks)}개의 병목 구간 발견!")
            print(f"임계값 (평균의 2배): {threshold:.2f} ms")
            print(f"\n병목 구간 (상위 10개):")
            for timestamp, response_time in bottlenecks.nlargest(10).items():
                print(f"  {timestamp.strftime('%Y-%m-%d %H:%M:%S')}: {response_time:.2f} ms")
        else:
            print("✅ 병목 구간 없음 - 안정적인 성능")

        return response_time_series, threshold

    def analyze_latency_distribution(self):
        """레이턴시 분포 분석"""
        print("\n" + "="*70)
        print("📈 레이턴시 분포 (Latency Distribution)")
        print("="*70)

        bins = [0, 50, 100, 200, 500, 1000, 2000, float('inf')]
        labels = ['0-50ms', '50-100ms', '100-200ms', '200-500ms',
                 '500-1000ms', '1-2s', '>2s']

        self.df['latency_bin'] = pd.cut(self.df['elapsed'], bins=bins, labels=labels)
        distribution = self.df['latency_bin'].value_counts().sort_index()

        print("\n응답 시간 분포:")
        for label, count in distribution.items():
            percentage = (count / len(self.df)) * 100
            bar = '█' * int(percentage / 2)
            print(f"  {label:12s}: {count:6,} ({percentage:5.1f}%) {bar}")

    def create_graphs(self, tps, response_time_series, threshold):
        """성능 그래프 생성"""
        print("\n" + "="*70)
        print("📊 그래프 생성 중...")
        print("="*70)

        fig, axes = plt.subplots(4, 1, figsize=(16, 18))
        fig.suptitle('Payment Service Performance Analysis', fontsize=16, fontweight='bold')

        # 1. TPS 그래프
        axes[0].plot(tps.index, tps.values, linewidth=2, color='#2196F3')
        axes[0].axhline(y=tps.mean(), color='red', linestyle='--',
                       label=f'Average: {tps.mean():.2f} TPS', linewidth=2)
        axes[0].set_title('Transactions Per Second (TPS)', fontsize=14, fontweight='bold')
        axes[0].set_ylabel('TPS', fontsize=12)
        axes[0].grid(True, alpha=0.3)
        axes[0].legend(fontsize=10)
        axes[0].set_xlabel('Time', fontsize=12)

        # 2. 응답 시간 추이
        axes[1].plot(response_time_series.index, response_time_series.values,
                    linewidth=2, color='#4CAF50')
        axes[1].axhline(y=response_time_series.mean(), color='red', linestyle='--',
                       label=f'Average: {response_time_series.mean():.2f} ms', linewidth=2)
        if threshold:
            axes[1].axhline(y=threshold, color='orange', linestyle='--',
                           label=f'Threshold (2x avg): {threshold:.2f} ms', linewidth=2)
        axes[1].set_title('Average Response Time', fontsize=14, fontweight='bold')
        axes[1].set_ylabel('Response Time (ms)', fontsize=12)
        axes[1].grid(True, alpha=0.3)
        axes[1].legend(fontsize=10)
        axes[1].set_xlabel('Time', fontsize=12)

        # 3. 에러율
        error_rate = self.df.set_index('timeStamp')['success'].resample('5S').apply(
            lambda x: (1 - x.mean()) * 100 if len(x) > 0 else 0
        )
        axes[2].plot(error_rate.index, error_rate.values, linewidth=2, color='#F44336')
        axes[2].axhline(y=error_rate.mean(), color='orange', linestyle='--',
                       label=f'Average: {error_rate.mean():.2f}%', linewidth=2)
        axes[2].set_title('Error Rate (%)', fontsize=14, fontweight='bold')
        axes[2].set_ylabel('Error Rate (%)', fontsize=12)
        axes[2].grid(True, alpha=0.3)
        axes[2].legend(fontsize=10)
        axes[2].set_xlabel('Time', fontsize=12)
        axes[2].set_ylim(bottom=0)

        # 4. 응답 시간 분포 (히스토그램)
        axes[3].hist(self.df['elapsed'], bins=50, edgecolor='black',
                    alpha=0.7, color='#9C27B0')
        axes[3].axvline(x=self.df['elapsed'].mean(), color='red', linestyle='--',
                       label=f'Mean: {self.df["elapsed"].mean():.2f} ms', linewidth=2)
        axes[3].axvline(x=self.df['elapsed'].median(), color='green', linestyle='--',
                       label=f'Median: {self.df["elapsed"].median():.2f} ms', linewidth=2)
        axes[3].axvline(x=self.df['elapsed'].quantile(0.95), color='orange', linestyle='--',
                       label=f'P95: {self.df["elapsed"].quantile(0.95):.2f} ms', linewidth=2)
        axes[3].set_title('Response Time Distribution', fontsize=14, fontweight='bold')
        axes[3].set_xlabel('Response Time (ms)', fontsize=12)
        axes[3].set_ylabel('Frequency', fontsize=12)
        axes[3].grid(True, alpha=0.3, axis='y')
        axes[3].legend(fontsize=10)

        plt.tight_layout()

        # 저장
        output_path = self.output_dir / 'performance_analysis.png'
        plt.savefig(output_path, dpi=150, bbox_inches='tight')
        print(f"✅ 그래프 저장: {output_path}")
        plt.close()

    def generate_report(self):
        """종합 리포트 생성"""
        report_path = self.output_dir / 'analysis_report.txt'

        with open(report_path, 'w', encoding='utf-8') as f:
            f.write("="*70 + "\n")
            f.write("Payment Service Performance Test - Analysis Report\n")
            f.write("="*70 + "\n")
            f.write(f"생성 시간: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"데이터 파일: {self.jtl_path.name}\n")
            f.write(f"총 요청 수: {len(self.df):,}\n")
            f.write("\n")

            # 주요 지표
            f.write("주요 성능 지표:\n")
            f.write(f"  평균 응답시간: {self.df['elapsed'].mean():.2f} ms\n")
            f.write(f"  P95 응답시간: {self.df['elapsed'].quantile(0.95):.2f} ms\n")
            f.write(f"  P99 응답시간: {self.df['elapsed'].quantile(0.99):.2f} ms\n")

            tps = self.df.set_index('timeStamp').resample('1S').size()
            f.write(f"  평균 TPS: {tps.mean():.2f}\n")
            f.write(f"  최대 TPS: {tps.max():.2f}\n")

            success_rate = (self.df['success'].sum() / len(self.df)) * 100
            f.write(f"  성공률: {success_rate:.2f}%\n")

        print(f"✅ 리포트 저장: {report_path}")

    def run_analysis(self):
        """전체 분석 실행"""
        if not self.load_data():
            return False

        self.print_summary()
        tps = self.analyze_tps()
        self.analyze_success_rate()
        self.analyze_by_endpoint()
        response_time_series, threshold = self.detect_bottlenecks()
        self.analyze_latency_distribution()
        self.create_graphs(tps, response_time_series, threshold)
        self.generate_report()

        print("\n" + "="*70)
        print("✅ 분석 완료!")
        print("="*70)
        print(f"\n생성된 파일:")
        print(f"  📊 그래프: {self.output_dir}/performance_analysis.png")
        print(f"  📄 리포트: {self.output_dir}/analysis_report.txt")
        print()

        return True


def main():
    """메인 함수"""
    if len(sys.argv) < 2:
        print("사용법: python3 analyze.py <jtl_file_path>")
        print("\n예시:")
        print("  python3 load-test/scripts/analyze.py load-test/results/20240315_120000/results.jtl")
        sys.exit(1)

    jtl_file = sys.argv[1]

    if not Path(jtl_file).exists():
        print(f"❌ 파일을 찾을 수 없습니다: {jtl_file}")
        sys.exit(1)

    # 분석 실행
    analyzer = JMeterAnalyzer(jtl_file)
    success = analyzer.run_analysis()

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
