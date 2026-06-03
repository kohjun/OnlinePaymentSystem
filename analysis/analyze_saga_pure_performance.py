import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import os
import sys

# Constants for Latency Breakdown (based on prior single-user log analysis proportions)
RESERVE_PROP = 0.176  # Inventory Reserve (Phase 1)
ORDER_PROP = 0.074    # Order Create (Phase 1)
COMPENSATION_PROP = 0.062 # Compensation Rollback (Order Cancel + Inventory Release)
CORE_TIME_PROP = RESERVE_PROP + ORDER_PROP + COMPENSATION_PROP
OVERHEAD_PROP = 1.0 - CORE_TIME_PROP # Remaining time is attributed to overhead/queuing

def analyze_saga_pure_performance(jtl_path, output_dir='saga_performance_output'):
    """
    Analyzes the JTL file to visualize the pure performance (Success Rate, TPS, Latency) 
    and simulates the internal breakdown and inventory status.
    """
    if not os.path.exists(jtl_path):
        print(f"Error: File not found - {jtl_path}")
        print("Please ensure you have run the JMeter test to generate the JTL file.")
        return

    try:
        # 1. Load Data and Filtering
        try:
            df = pd.read_csv(jtl_path, encoding='utf-8')
        except UnicodeDecodeError:
            print("Warning: UTF-8 decoding failed. Retrying with CP949 (Windows) encoding.")
            df = pd.read_csv(jtl_path, encoding='cp949')
        
        df_filtered = df[df['label'].str.contains('Complete Reservation', na=False)].copy()
        
        if df_filtered.empty:
            print("Error: No data found for 'Complete Reservation (Saga Test)' label. Check your JMeter file's label.")
            return

        # 2. Calculate Performance Metrics
        total_requests = len(df_filtered)
        if total_requests > 1:
            start_time = df_filtered['timeStamp'].min()
            end_time = df_filtered['timeStamp'].max()
            duration_s = max((end_time - start_time) / 1000.0, 1.0) 
        else:
            duration_s = 1.0 

        throughput_tps = total_requests / duration_s
        latency_avg = df_filtered['elapsed'].mean()
        latency_95th = df_filtered['elapsed'].quantile(0.95)

        # Compensation Success Rate (Expected failure is HTTP 400)
        compensation_success_count = len(df_filtered[df_filtered['responseCode'].astype(str).str.startswith('400')])
        compensation_success_rate = (compensation_success_count / total_requests) * 100

        # 3. Latency Breakdown Simulation
        breakdown = {
            'Overhead/Queueing': latency_avg * OVERHEAD_PROP,
            'Reserve (Phase 1)': latency_avg * RESERVE_PROP,
            'Order Create (Phase 1)': latency_avg * ORDER_PROP,
            'Compensation Rollback': latency_avg * COMPENSATION_PROP,
        }
        # Ensure all breakdown sums up to latency_avg (minor floating point adjustment)
        breakdown['Overhead/Queueing'] += latency_avg - sum(breakdown.values()) 
        
        # 4. Create Result Directory
        os.makedirs(output_dir, exist_ok=True)

        # 5. --- Plot 1: Phase-by-Phase Latency Breakdown (Stacked Bar) ---
        
        stages = list(breakdown.keys())
        times = list(breakdown.values())
        
        plt.figure(figsize=(10, 6))
        
        # Single stacked bar representing the total average latency
        bars = plt.bar(
            ['Avg. Total Latency'], 
            [sum(times)],
            color=None,
            edgecolor='black',
            alpha=0.6,
            label='Total'
        )

        # Stacked bars for breakdown
        bottom_time = 0
        for i, (stage, time) in enumerate(breakdown.items()):
            plt.bar(
                ['Avg. Total Latency'], 
                [time],
                bottom=bottom_time,
                label=stage,
                color=plt.cm.Spectral(i / len(stages)),
                alpha=0.9
            )
            # Add time label inside the segment
            if time > (sum(times) * 0.05): # Only label if segment is large enough
                plt.text(
                    0, 
                    bottom_time + time / 2, 
                    f'{time:.0f} ms', 
                    ha='center', 
                    va='center', 
                    color='white' if i != 0 else 'black', 
                    fontsize=10, 
                    fontweight='bold'
                )
            bottom_time += time
        
        plt.title('Phase-by-Phase Latency Breakdown (Simulated)', fontsize=14)
        plt.ylabel('Time (ms)', fontsize=12)
        plt.legend(loc='upper right')
        plt.ylim(0, bottom_time * 1.1)
        plt.grid(axis='y', linestyle='--', alpha=0.5)
        plt.tight_layout()
        latency_breakdown_file = os.path.join(output_dir, 'latency_breakdown.png')
        plt.savefig(latency_breakdown_file)
        plt.close()
        print(f"✅ Latency Breakdown chart generated: {latency_breakdown_file}")

        # 6. --- Plot 2: Inventory Change Visualization (Simulated Consistency) ---

        INITIAL_STOCK = 200
        QUANTITY_PER_REQ = 1
        
        # State Simulation (Verifiable points)
        inventory_states = pd.DataFrame({
            'State': ['Initial State (T=0)', 'Peak Reserved (T=Reserve)', 'Final State (T=Compensated)'],
            'Available': [INITIAL_STOCK, INITIAL_STOCK - QUANTITY_PER_REQ, INITIAL_STOCK],
            'Reserved': [0, QUANTITY_PER_REQ, 0],
            'Total': [INITIAL_STOCK, INITIAL_STOCK, INITIAL_STOCK]
        })

        x = np.arange(len(inventory_states))  # the label locations
        width = 0.35  # the width of the bars

        fig, ax = plt.subplots(figsize=(10, 6))
        
        # Plot Available Quantity
        rects1 = ax.bar(x - width/2, inventory_states['Available'], width, label='Available Stock', color='#1E90FF')
        # Plot Reserved Quantity
        rects2 = ax.bar(x + width/2, inventory_states['Reserved'], width, label='Reserved Stock', color='#FF4500')

        # Add some text for labels, title and custom x-axis tick labels, etc.
        ax.set_ylabel('Quantity', fontsize=12)
        ax.set_title('Inventory State Change during Compensation (Simulation)', fontsize=14)
        ax.set_xticks(x)
        ax.set_xticklabels(inventory_states['State'])
        ax.set_ylim(0, INITIAL_STOCK * 1.1)
        ax.legend()
        ax.grid(axis='y', linestyle='--', alpha=0.5)

        # Label helper function
        def autolabel(rects):
            for rect in rects:
                height = rect.get_height()
                ax.annotate(f'{height}',
                            xy=(rect.get_x() + rect.get_width() / 2, height),
                            xytext=(0, 3),  # 3 points vertical offset
                            textcoords="offset points",
                            ha='center', va='bottom')

        autolabel(rects1)
        autolabel(rects2)

        plt.tight_layout()
        inventory_file = os.path.join(output_dir, 'inventory_change_simulation.png')
        plt.savefig(inventory_file)
        plt.close()
        print(f"✅ Inventory Change simulation chart generated: {inventory_file}")

        # 7. --- Console Summary Output (Original Plot 1 & 2 combined) ---
        
        print("\n--- Saga Compensation Pure Performance Analysis Summary ---")
        print(f"1. Total Requests Processed: {total_requests} reqs")
        print(f"2. Compensation Success Requests (4xx): {compensation_success_count} reqs")
        print(f"3. Compensation Success Rate: {compensation_success_rate:.2f}%")
        print(f"4. Average Latency (Total): {latency_avg:.0f} ms")
        print(f"5. 95th Percentile Latency: {latency_95th:.0f} ms")
        print(f"6. Throughput (TPS): {throughput_tps:.2f}")
        print("\n--- Phase Latency Breakdown (Avg) ---")
        for stage, time in breakdown.items():
            print(f"  - {stage}: {time:.0f} ms")


    except Exception as e:
        print(f"❌ Error occurred during data analysis: {e}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python analyze_saga_pure_performance.py <jtl_file_path>")
        sys.exit(1)
    
    analyze_saga_pure_performance(sys.argv[1])