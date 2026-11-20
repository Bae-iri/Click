# 기억 재사용 실기기 실험 집계

에피소드 216회, 회기 예산 25000ms.

## 비교군 × 단계

| 비교군 | 단계 | 예약 | 준비 | 독립 성공 | 성공률 | 실패 포함 평균 시간(ms) | 평균 모델 호출 | 기억 제안 회기 | 기억 제안 회기의 성공 | 잘못된 VERIFIED |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | learn | 36 | 34 | 7 | 0.1944 | 21489.5 | 1.088 | 0 | 0/0 | 10 |
| A | retain | 12 | 11 | 2 | 0.1667 | 21829.3 | 1.091 | 0 | 0/0 | 3 |
| A | mirror | 24 | 22 | 6 | 0.25 | 20353.3 | 1.045 | 0 | 0/0 | 7 |
| B | learn | 36 | 33 | 6 | 0.1667 | 21942.2 | 1.0 | 0 | 0/0 | 11 |
| B | retain | 12 | 11 | 2 | 0.1667 | 21580.8 | 1.0 | 0 | 0/0 | 4 |
| B | mirror | 24 | 22 | 4 | 0.1667 | 21871.0 | 1.0 | 0 | 0/0 | 8 |
| C | learn | 36 | 33 | 6 | 0.1667 | 21882.2 | 1.0 | 0 | 0/0 | 9 |
| C | retain | 12 | 11 | 1 | 0.0833 | 23268.6 | 1.0 | 0 | 0/0 | 3 |
| C | mirror | 24 | 22 | 2 | 0.0833 | 23359.3 | 1.0 | 0 | 0/0 | 8 |

## 회기별 성공 (O=독립 성공)

| 비교군 | pass@1 | pass@3 | 학습 2회 이상 성공 | 실패 회복 | 과제별 learn1·2·3·retain·mirror1·2 |
|---|---:|---:|---:|---:|---|
| A | 2/12 | 4/12 | 2 | 2/10 | chrome_menu .OOOOO, chrome_new_tab OOOOO., chrome_settings ......, clock_stopwatch ....O., clock_timer ....OO, clock_world ......, settings_display ..O..., settings_notifications ......, settings_sound ......, youtube_search O....., youtube_shorts ......, youtube_subscriptions ...... |
| B | 3/12 | 3/12 | 2 | 0/9 | chrome_menu O.OO.O, chrome_new_tab OOOOOO, chrome_settings ......, clock_stopwatch ....O., clock_timer ......, clock_world ......, settings_display O....., settings_notifications ......, settings_sound ......, youtube_search ......, youtube_shorts ......, youtube_subscriptions ...... |
| C | 3/12 | 4/12 | 2 | 1/9 | chrome_menu O.OO.., chrome_new_tab O...OO, chrome_settings ......, clock_stopwatch ......, clock_timer ......, clock_world ......, settings_display OO...., settings_notifications ..O..., settings_sound ......, youtube_search ......, youtube_shorts ......, youtube_subscriptions ...... |

## 대응 비교 (같은 과제·회기, 준비 완료 쌍)

- **B-A learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": -63.6061, "cluster_bootstrap_95ci": [-283.6061, 135.0303]}, "model_calls": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}}`
- **B-A retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": -271.1818, "cluster_bootstrap_95ci": [-800.0, 0.0]}, "model_calls": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}}`
- **B-A mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.3182, 0.0909]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1655.6364, "cluster_bootstrap_95ci": [-1606.2727, 5728.3182]}, "model_calls": {"mean_diff_right_minus_left": -0.0455, "cluster_bootstrap_95ci": [-0.1364, 0.0]}}`
- **B-A retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0606, "cluster_bootstrap_95ci": [-0.2121, 0.0606]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1013.3636, "cluster_bootstrap_95ci": [-1089.9394, 3722.9394]}, "model_calls": {"mean_diff_right_minus_left": -0.0606, "cluster_bootstrap_95ci": [-0.1818, 0.0]}}`
- **C-B learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [-0.1515, 0.1212]}, "penalized_time_ms": {"mean_diff_right_minus_left": -65.4848, "cluster_bootstrap_95ci": [-2342.5758, 2662.7273]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **C-B retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1841.2727, "cluster_bootstrap_95ci": [0.0, 5517.4545]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **C-B mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2273, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1623.6364, "cluster_bootstrap_95ci": [-166.9091, 4124.4545]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **C-B retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.1818, -0.0303]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1696.1818, "cluster_bootstrap_95ci": [530.2121, 3419.2121]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **C-A learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [-0.1515, 0.1212]}, "penalized_time_ms": {"mean_diff_right_minus_left": -129.0909, "cluster_bootstrap_95ci": [-2490.6364, 2703.4242]}, "model_calls": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}}`
- **C-A retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1570.0909, "cluster_bootstrap_95ci": [-763.3636, 5473.6364]}, "model_calls": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}}`
- **C-A mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.1818, "cluster_bootstrap_95ci": [-0.4545, 0.0455]}, "penalized_time_ms": {"mean_diff_right_minus_left": 3279.2727, "cluster_bootstrap_95ci": [-894.1364, 8346.8182]}, "model_calls": {"mean_diff_right_minus_left": -0.0455, "cluster_bootstrap_95ci": [-0.1364, 0.0]}}`
- **C-A retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.1515, "cluster_bootstrap_95ci": [-0.3333, -0.0303]}, "penalized_time_ms": {"mean_diff_right_minus_left": 2709.5455, "cluster_bootstrap_95ci": [618.9697, 5686.0]}, "model_calls": {"mean_diff_right_minus_left": -0.0606, "cluster_bootstrap_95ci": [-0.1818, 0.0]}}`

## 정답 양성 대조 확인 7과제

| 비교군 | 독립 성공/예약 | 잘못된 VERIFIED | 기억 제안 회기 |
|---|---:|---:|---:|
| A | 14/42 | 11 | 0 |
| B | 11/42 | 11 | 0 |
| C | 9/42 | 8 | 0 |

## 완료 조건 결속과 AI2 경로 형성

```json
{
 "completion_binding": {
  "A": {
   "first_planner_responses": 0,
   "bound": 0,
   "rejected_satisfied_at_binding": 0
  },
  "B": {
   "first_planner_responses": 0,
   "bound": 0,
   "rejected_satisfied_at_binding": 0
  },
  "C": {
   "first_planner_responses": 0,
   "bound": 0,
   "rejected_satisfied_at_binding": 0
  }
 },
 "ai2_analyses": {}
}
```

## 앱별 성공률

| 비교군 | 앱 | 성공/예약 |
|---|---|---:|
| A | com.android.chrome | 10/18 |
| A | com.android.settings | 1/18 |
| A | com.google.android.youtube | 1/18 |
| A | com.sec.android.app.clockpackage | 3/18 |
| B | com.android.chrome | 10/18 |
| B | com.android.settings | 1/18 |
| B | com.google.android.youtube | 0/18 |
| B | com.sec.android.app.clockpackage | 1/18 |
| C | com.android.chrome | 6/18 |
| C | com.android.settings | 3/18 |
| C | com.google.android.youtube | 0/18 |
| C | com.sec.android.app.clockpackage | 0/18 |

## 회수 진단과 그림자 변형

```json
{
 "B/learn": {
  "all": {
   "recalls": 70,
   "gate_None": 70,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 22
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 12,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 22
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 22
  }
 },
 "B/mirror": {
  "all": {
   "recalls": 44,
   "gate_None": 44,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 12,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 10,
   "blocked_by_adverse": 6
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 12,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 4,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 12,
   "has_two_confirmed_routes": 2,
   "has_same_goal_screen_step": 10,
   "blocked_by_adverse": 6
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 36,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 24
  }
 },
 "B/retain": {
  "all": {
   "recalls": 24,
   "gate_None": 24,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 20,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 16,
   "blocked_by_adverse": 12
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 20,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 6,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 20,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 16,
   "blocked_by_adverse": 12
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 20,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 16,
   "blocked_by_adverse": 12
  }
 },
 "C/learn": {
  "all": {
   "recalls": 66,
   "gate_None": 66,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 36,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 24
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 36,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 8,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 36,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 24
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 36,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 32,
   "blocked_by_adverse": 24
  }
 },
 "C/mirror": {
  "all": {
   "recalls": 44,
   "gate_None": 44,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 6,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 4,
   "blocked_by_adverse": 2
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 6,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 2,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 6,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 4,
   "blocked_by_adverse": 2
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 36,
   "blocked_by_adverse": 24
  }
 },
 "C/retain": {
  "all": {
   "recalls": 24,
   "gate_None": 24,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 18,
   "blocked_by_adverse": 12
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 6,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 2,
   "has_same_goal_screen_step": 18,
   "blocked_by_adverse": 12
  },
  "normalized": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 18,
   "blocked_by_adverse": 12
  }
 }
}
```

## 모델 제공자 오류

```json
{
 "by_arm": {
  "A": {
   "calls": 74,
   "errors": {
    "ReadTimeout": 11,
    "ValueError": 1
   },
   "ai2_server_proposals": 0
  },
  "B": {
   "calls": 69,
   "errors": {
    "ReadTimeout": 6
   },
   "ai2_server_proposals": 0
  },
  "C": {
   "calls": 67,
   "errors": {
    "ReadTimeout": 12,
    "ValueError": 1
   },
   "ai2_server_proposals": 0
  }
 }
}
```
