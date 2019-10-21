package org.hotswap.agent.plugin.spring.testBeansHotswap;

import javax.annotation.PostConstruct;

import org.hotswap.agent.plugin.spring.testBeans.BeanPostConstruct;
import org.springframework.stereotype.Component;

/**
 * @author krylov
 */
@Component
public class BeanPostConstructImpl2 implements BeanPostConstruct {

  private String value;

  @PostConstruct
  public void b() {
    value = newMethod();
  }

  public String getValue() {
    return value;
  }

  private String oldMethod() {
    return "old value";
  }

  private String newMethod() {
    return "new value";
  }
}
