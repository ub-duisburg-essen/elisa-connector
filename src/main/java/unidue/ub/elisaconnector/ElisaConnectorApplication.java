package unidue.ub.elisaconnector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@SpringBootApplication
@EnableFeignClients
@EnableEurekaClient
public class ElisaConnectorApplication  extends WebSecurityConfigurerAdapter {

    public static void main(String[] args) {
        SpringApplication.run(ElisaConnectorApplication.class, args);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.httpBasic()
                .and()
                .authorizeRequests()
                .antMatchers("/sendEav").permitAll().and()
                .csrf().disable();
    }

}