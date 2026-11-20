# 기억 재사용 실기기 실험 집계

에피소드 216회, 회기 예산 25000ms.

## 비교군 × 단계

| 비교군 | 단계 | 예약 | 준비 | 독립 성공 | 성공률 | 실패 포함 평균 시간(ms) | 평균 모델 호출 | 기억 제안 회기 | 기억 제안 회기의 성공 | 잘못된 VERIFIED |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | learn | 36 | 34 | 11 | 0.3056 | 19021.2 | 1.0 | 0 | 0/0 | 4 |
| A | retain | 12 | 11 | 4 | 0.3333 | 18865.8 | 1.0 | 0 | 0/0 | 1 |
| A | mirror | 24 | 22 | 12 | 0.5 | 15662.2 | 1.091 | 0 | 0/0 | 2 |
| B | learn | 36 | 33 | 11 | 0.3056 | 19429.1 | 1.061 | 0 | 0/0 | 3 |
| B | retain | 12 | 11 | 3 | 0.25 | 20081.2 | 1.0 | 0 | 0/0 | 2 |
| B | mirror | 24 | 22 | 6 | 0.25 | 20395.3 | 1.045 | 0 | 0/0 | 2 |
| C | learn | 36 | 33 | 12 | 0.3333 | 18962.2 | 1.0 | 0 | 0/0 | 5 |
| C | retain | 12 | 11 | 4 | 0.3333 | 19155.2 | 1.091 | 0 | 0/0 | 2 |
| C | mirror | 24 | 22 | 9 | 0.375 | 17758.5 | 1.045 | 0 | 0/0 | 2 |

## 회기별 성공 (O=독립 성공)

| 비교군 | pass@1 | pass@3 | 학습 2회 이상 성공 | 실패 회복 | 과제별 learn1·2·3·retain·mirror1·2 |
|---|---:|---:|---:|---:|---|
| A | 5/12 | 5/12 | 4 | 0/7 | chrome_menu OOOOO., chrome_new_tab OO.OOO, chrome_settings ....O., clock_stopwatch ....OO, clock_timer ....OO, clock_world ......, settings_display OO.OOO, settings_notifications OOOOOO, settings_sound ......, youtube_search O....., youtube_shorts ......, youtube_subscriptions ...... |
| B | 3/12 | 5/12 | 4 | 2/9 | chrome_menu O.O..., chrome_new_tab OOOOOO, chrome_settings ..O..., clock_stopwatch ......, clock_timer ......, clock_world ......, settings_display .OOOOO, settings_notifications OOOOOO, settings_sound ......, youtube_search ......, youtube_shorts ......, youtube_subscriptions ...... |
| C | 4/12 | 5/12 | 4 | 1/8 | chrome_menu O.OOOO, chrome_new_tab OOOOOO, chrome_settings .....O, clock_stopwatch ..O..., clock_timer ......, clock_world ......, settings_display OOOOOO, settings_notifications OOOOOO, settings_sound ......, youtube_search ......, youtube_shorts ......, youtube_subscriptions ...... |

## 대응 비교 (같은 과제·회기, 준비 완료 쌍)

- **B-A learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0303, "cluster_bootstrap_95ci": [-0.0606, 0.1212]}, "penalized_time_ms": {"mean_diff_right_minus_left": -157.4545, "cluster_bootstrap_95ci": [-2188.3636, 1988.3939]}, "model_calls": {"mean_diff_right_minus_left": 0.0606, "cluster_bootstrap_95ci": [0.0, 0.1818]}}`
- **B-A retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1325.9091, "cluster_bootstrap_95ci": [-1066.5455, 5314.0909]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **B-A mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.2727, "cluster_bootstrap_95ci": [-0.5, -0.0455]}, "penalized_time_ms": {"mean_diff_right_minus_left": 5163.3636, "cluster_bootstrap_95ci": [1442.5, 9702.9545]}, "model_calls": {"mean_diff_right_minus_left": -0.0455, "cluster_bootstrap_95ci": [-0.2727, 0.1364]}}`
- **B-A retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.2121, "cluster_bootstrap_95ci": [-0.3939, -0.0606]}, "penalized_time_ms": {"mean_diff_right_minus_left": 3884.2121, "cluster_bootstrap_95ci": [966.2121, 7389.7273]}, "model_calls": {"mean_diff_right_minus_left": -0.0303, "cluster_bootstrap_95ci": [-0.1818, 0.0909]}}`
- **C-B learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0303, "cluster_bootstrap_95ci": [-0.0606, 0.1212]}, "penalized_time_ms": {"mean_diff_right_minus_left": -509.3333, "cluster_bootstrap_95ci": [-2131.6667, 1029.7879]}, "model_calls": {"mean_diff_right_minus_left": -0.0606, "cluster_bootstrap_95ci": [-0.1818, 0.0]}}`
- **C-B retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0909, "cluster_bootstrap_95ci": [0.0, 0.2727]}, "penalized_time_ms": {"mean_diff_right_minus_left": -1010.1818, "cluster_bootstrap_95ci": [-4682.8182, 1244.0]}, "model_calls": {"mean_diff_right_minus_left": 0.0909, "cluster_bootstrap_95ci": [0.0, 0.2727]}}`
- **C-B mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.1364, "cluster_bootstrap_95ci": [0.0, 0.3182]}, "penalized_time_ms": {"mean_diff_right_minus_left": -2876.5, "cluster_bootstrap_95ci": [-6575.7727, -342.2727]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [-0.1364, 0.1364]}}`
- **C-B retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.1212, "cluster_bootstrap_95ci": [0.0, 0.303]}, "penalized_time_ms": {"mean_diff_right_minus_left": -2254.3939, "cluster_bootstrap_95ci": [-5727.2727, 0.0]}, "model_calls": {"mean_diff_right_minus_left": 0.0303, "cluster_bootstrap_95ci": [-0.0606, 0.1515]}}`
- **C-A learn**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0606, "cluster_bootstrap_95ci": [-0.0606, 0.1818]}, "penalized_time_ms": {"mean_diff_right_minus_left": -666.7879, "cluster_bootstrap_95ci": [-2712.0606, 1603.5758]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}}`
- **C-A retain**: `{"pairs": 11, "task_clusters": 11, "success": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [0.0, 0.0]}, "penalized_time_ms": {"mean_diff_right_minus_left": 315.7273, "cluster_bootstrap_95ci": [-241.0, 845.6364]}, "model_calls": {"mean_diff_right_minus_left": 0.0909, "cluster_bootstrap_95ci": [0.0, 0.2727]}}`
- **C-A mirror**: `{"pairs": 22, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.1364, "cluster_bootstrap_95ci": [-0.4091, 0.0909]}, "penalized_time_ms": {"mean_diff_right_minus_left": 2286.8636, "cluster_bootstrap_95ci": [-1919.8636, 7413.7273]}, "model_calls": {"mean_diff_right_minus_left": -0.0455, "cluster_bootstrap_95ci": [-0.1364, 0.0]}}`
- **C-A retain+mirror**: `{"pairs": 33, "task_clusters": 11, "success": {"mean_diff_right_minus_left": -0.0909, "cluster_bootstrap_95ci": [-0.2727, 0.0606]}, "penalized_time_ms": {"mean_diff_right_minus_left": 1629.8182, "cluster_bootstrap_95ci": [-1086.3939, 4981.3939]}, "model_calls": {"mean_diff_right_minus_left": 0.0, "cluster_bootstrap_95ci": [-0.0909, 0.0909]}}`

## 정답 양성 대조 확인 7과제

| 비교군 | 독립 성공/예약 | 잘못된 VERIFIED | 기억 제안 회기 |
|---|---:|---:|---:|
| A | 24/42 | 0 | 0 |
| B | 19/42 | 0 | 0 |
| C | 23/42 | 0 | 0 |

## 완료 조건 결속과 AI2 경로 형성

```json
{
 "completion_binding": {
  "A": {
   "first_planner_responses": 63,
   "bound": 50,
   "rejected_satisfied_at_binding": 1
  },
  "B": {
   "first_planner_responses": 63,
   "bound": 49,
   "rejected_satisfied_at_binding": 1
  },
  "C": {
   "first_planner_responses": 61,
   "bound": 52,
   "rejected_satisfied_at_binding": 0
  }
 },
 "ai2_analyses": {
  "B": {
   "user_confirmed_tasks": 20,
   "route_formed": 14,
   "promoted_by_user_confirmed_result": 7,
   "goals_with_two_or_more_routes": [
    "chrome_menu:f30bee",
    "chrome_new_tab:0cff7c",
    "chrome_new_tab:e6bfc7",
    "settings_notifications:3e7fa8",
    "settings_notifications:4a99c1"
   ]
  },
  "C": {
   "user_confirmed_tasks": 25,
   "route_formed": 18,
   "promoted_by_user_confirmed_result": 8,
   "goals_with_two_or_more_routes": [
    "chrome_menu:526a74",
    "chrome_menu:f30bee",
    "chrome_new_tab:0cff7c",
    "chrome_new_tab:e6bfc7",
    "settings_notifications:3e7fa8",
    "settings_notifications:4a99c1"
   ]
  }
 }
}
```

## 앱별 성공률

| 비교군 | 앱 | 성공/예약 |
|---|---|---:|
| A | com.android.chrome | 11/18 |
| A | com.android.settings | 11/18 |
| A | com.google.android.youtube | 1/18 |
| A | com.sec.android.app.clockpackage | 4/18 |
| B | com.android.chrome | 9/18 |
| B | com.android.settings | 11/18 |
| B | com.google.android.youtube | 0/18 |
| B | com.sec.android.app.clockpackage | 0/18 |
| C | com.android.chrome | 12/18 |
| C | com.android.settings | 12/18 |
| C | com.google.android.youtube | 0/18 |
| C | com.sec.android.app.clockpackage | 1/18 |

## 회수 진단과 그림자 변형

```json
{
 "B/learn": {
  "all": {
   "recalls": 75,
   "gate_None": 75,
   "completion_known": 3
  },
  "exact": {
   "remembered": 1,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 51,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 42,
   "blocked_by_adverse": 16
  },
  "exact_no_failure_memory": {
   "remembered": 1,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 51,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 10,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 51,
   "has_two_confirmed_routes": 12,
   "has_same_goal_screen_step": 42,
   "blocked_by_adverse": 16
  },
  "normalized": {
   "remembered": 1,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 51,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 42,
   "blocked_by_adverse": 16
  }
 },
 "B/mirror": {
  "all": {
   "recalls": 52,
   "gate_None": 52,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 28,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 20,
   "blocked_by_adverse": 10
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 28,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 8,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 4,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 28,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 20,
   "blocked_by_adverse": 10
  },
  "normalized": {
   "remembered": 4,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 52,
   "has_two_confirmed_routes": 14,
   "has_same_goal_screen_step": 44,
   "blocked_by_adverse": 18
  }
 },
 "B/retain": {
  "all": {
   "recalls": 22,
   "gate_None": 22,
   "completion_known": 0
  },
  "exact": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 8
  },
  "exact_no_failure_memory": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 12,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 8
  },
  "normalized": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 8
  }
 },
 "C/learn": {
  "all": {
   "recalls": 66,
   "gate_None": 66,
   "completion_known": 0
  },
  "exact": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 40,
   "blocked_by_adverse": 14
  },
  "exact_no_failure_memory": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 24,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 8,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 12,
   "has_same_goal_screen_step": 40,
   "blocked_by_adverse": 14
  },
  "normalized": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 40,
   "has_two_confirmed_routes": 4,
   "has_same_goal_screen_step": 40,
   "blocked_by_adverse": 14
  }
 },
 "C/mirror": {
  "all": {
   "recalls": 50,
   "gate_None": 50,
   "completion_known": 0
  },
  "exact": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 16,
   "blocked_by_adverse": 4
  },
  "exact_no_failure_memory": {
   "remembered": 0,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 0,
   "has_same_goal_screen_step": 10,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 6,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 22,
   "has_two_confirmed_routes": 6,
   "has_same_goal_screen_step": 16,
   "blocked_by_adverse": 4
  },
  "normalized": {
   "remembered": 6,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 50,
   "has_two_confirmed_routes": 14,
   "has_same_goal_screen_step": 44,
   "blocked_by_adverse": 20
  }
 },
 "C/retain": {
  "all": {
   "recalls": 26,
   "gate_None": 26,
   "completion_known": 2
  },
  "exact": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 26,
   "has_two_confirmed_routes": 8,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 10
  },
  "exact_no_failure_memory": {
   "remembered": 4,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 26,
   "has_two_confirmed_routes": 8,
   "has_same_goal_screen_step": 14,
   "blocked_by_adverse": 0
  },
  "exact_support_one": {
   "remembered": 6,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 26,
   "has_two_confirmed_routes": 10,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 10
  },
  "normalized": {
   "remembered": 2,
   "remembered_with_completion": 0,
   "has_same_goal_memory": 26,
   "has_two_confirmed_routes": 8,
   "has_same_goal_screen_step": 22,
   "blocked_by_adverse": 10
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
    "ReadTimeout": 11
   },
   "ai2_server_proposals": 0
  },
  "B": {
   "calls": 73,
   "errors": {
    "ReadTimeout": 10
   },
   "ai2_server_proposals": 0
  },
  "C": {
   "calls": 71,
   "errors": {
    "ReadTimeout": 10
   },
   "ai2_server_proposals": 0
  }
 }
}
```
