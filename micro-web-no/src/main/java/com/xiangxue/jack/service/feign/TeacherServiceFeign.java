package com.xiangxue.jack.service.feign;

import com.xiangxue.jack.api.TeacherService;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "MICRO-ORDER-NO")
public interface TeacherServiceFeign extends TeacherService {
}
