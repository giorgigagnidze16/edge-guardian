"use client";

import { PieChart, Pie, Cell } from "recharts";

interface MiniRingChartProps {
  value: number;
  total: number;
  color?: string;
  size?: number;
}

export function MiniRingChart({
  value,
  total,
  color = "#34d399",
  size = 36,
}: MiniRingChartProps) {
  const data = [
    { value },
    { value: Math.max(0, total - value) },
  ];

  return (
    <PieChart width={size} height={size}>
      <Pie
        data={data}
        cx="50%"
        cy="50%"
        innerRadius={size / 2 - 5}
        outerRadius={size / 2 - 1}
        startAngle={90}
        endAngle={-270}
        paddingAngle={0}
        dataKey="value"
        isAnimationActive={false}
      >
        <Cell fill={color} strokeWidth={0} />
        <Cell fill="var(--color-muted)" strokeWidth={0} />
      </Pie>
    </PieChart>
  );
}
