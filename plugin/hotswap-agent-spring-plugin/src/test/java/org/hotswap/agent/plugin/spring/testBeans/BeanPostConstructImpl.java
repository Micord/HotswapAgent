package org.hotswap.agent.plugin.spring.testBeans;

import javax.annotation.PostConstruct;

import org.springframework.stereotype.Component;

/**
 * @author krylov
 */
@Component
public class BeanPostConstructImpl implements BeanPostConstruct {

  private String value;

  @PostConstruct
  public void b() {
    value = oldMethod();
  }

  private String oldMethod() {
    return "old value";
  }

  public String getValue() {
    return value;
  }
}
