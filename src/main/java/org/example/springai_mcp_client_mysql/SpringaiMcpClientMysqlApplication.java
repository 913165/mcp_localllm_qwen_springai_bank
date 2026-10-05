package org.example.springai_mcp_client_mysql;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringaiMcpClientMysqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringaiMcpClientMysqlApplication.class, args);
    }

    @Bean
    public CommandLineRunner demo(ChatClient chatClient, ToolCallbackProvider mcpTools) {
        return args -> {
            String response = chatClient
                    .prompt("""
                    You are a UPI operations assistant for ABC Bank.
                    Use the MySQL database `abcbank_upi` (tables: upi_response_rules, incidents).

                    A new UPI error event has arrived:
                    component=upi-switch, stage=CBS_DEBIT, respCode=91,
                    reason="CBS response timeout after 30000ms", rrn=627810999001, amount=2500.00

                    Steps:
                    1. Look up the matching row in upi_response_rules using resp_code and stage.
                    2. Check the incidents table for an OPEN or IN_PROGRESS incident whose
                       error_signature is 'upi-switch|CBS_DEBIT|91|CBS response timeout after #ms'.
                    3. Do NOT insert or update anything yet.

                    Reply in this format:
                    - Category:
                    - Severity:
                    - Create incident needed (per rule):
                    - Existing open incident (id or none):
                    - Recommended action (one line):
                    """)

                    .tools(mcpTools)
                    .call()
                    .content();
            System.out.println(response);
        };
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
