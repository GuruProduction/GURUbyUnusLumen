//! Cerebrum CLI — command-line interface definitions.
//!
//! Uses a small manual argument parser (no external dependencies). The actual
//! command dispatch will be wired into the server/client later.

use serde::{Deserialize, Serialize};

/// CLI configuration.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CliConfig {
    pub server_address: String,
    pub timeout_ms: u64,
}

impl Default for CliConfig {
    fn default() -> Self {
        Self {
            server_address: "127.0.0.1:4222".to_string(),
            timeout_ms: 30000,
        }
    }
}

/// Parsed CLI command.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CliCommand {
    Query { query: String },
    Curate { subcommand: String, args: Vec<String> },
    Retrieve { id: String },
    Search { query: String },
    Graph { query: String },
    Consolidate,
    Prefetch { task_type: String },
    Status,
    Serve,
    Help,
    Unknown { argv: Vec<String> },
}

/// Parse command-line arguments into a `CliCommand`.
///
/// `args` should be the program arguments excluding the binary name, e.g.
/// `vec!["query".into(), "test query".into()]`.
pub fn parse_args(args: &[String]) -> CliCommand {
    if args.is_empty() {
        return CliCommand::Help;
    }

    match args[0].as_str() {
        "query" => CliCommand::Query {
            query: args.get(1).cloned().unwrap_or_default(),
        },
        "curate" => {
            let subcommand = args.get(1).cloned().unwrap_or_default();
            let rest = args.iter().skip(2).cloned().collect();
            CliCommand::Curate {
                subcommand,
                args: rest,
            }
        }
        "retrieve" => CliCommand::Retrieve {
            id: args.get(1).cloned().unwrap_or_default(),
        },
        "search" => CliCommand::Search {
            query: args.get(1).cloned().unwrap_or_default(),
        },
        "graph" => CliCommand::Graph {
            query: args.get(1).cloned().unwrap_or_default(),
        },
        "consolidate" => CliCommand::Consolidate,
        "prefetch" => CliCommand::Prefetch {
            task_type: args.get(1).cloned().unwrap_or_default(),
        },
        "status" => CliCommand::Status,
        "serve" => CliCommand::Serve,
        "help" | "--help" | "-h" => CliCommand::Help,
        _ => CliCommand::Unknown {
            argv: args.to_vec(),
        },
    }
}

/// Format a `CliCommand` as a human-readable description.
pub fn describe_command(command: &CliCommand) -> String {
    match command {
        CliCommand::Query { query } => format!("query: {}", query),
        CliCommand::Curate { subcommand, args } => {
            format!("curate {} {}", subcommand, args.join(" "))
        }
        CliCommand::Retrieve { id } => format!("retrieve: {}", id),
        CliCommand::Search { query } => format!("search: {}", query),
        CliCommand::Graph { query } => format!("graph: {}", query),
        CliCommand::Consolidate => "consolidate".to_string(),
        CliCommand::Prefetch { task_type } => format!("prefetch: {}", task_type),
        CliCommand::Status => "status".to_string(),
        CliCommand::Serve => "serve".to_string(),
        CliCommand::Help => "help".to_string(),
        CliCommand::Unknown { argv } => format!("unknown: {}", argv.join(" ")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_query_command() {
        let args = vec!["query".to_string(), "test query".to_string()];
        let command = parse_args(&args);
        assert_eq!(
            command,
            CliCommand::Query {
                query: "test query".to_string()
            }
        );
    }

    #[test]
    fn test_parse_curate_command() {
        let args = vec![
            "curate".to_string(),
            "add".to_string(),
            "domain=code".to_string(),
            "content=hello".to_string(),
        ];
        let command = parse_args(&args);
        assert_eq!(
            command,
            CliCommand::Curate {
                subcommand: "add".to_string(),
                args: vec!["domain=code".to_string(), "content=hello".to_string()],
            }
        );
    }

    #[test]
    fn test_parse_status_command() {
        let args = vec!["status".to_string()];
        let command = parse_args(&args);
        assert_eq!(command, CliCommand::Status);
    }

    #[test]
    fn test_parse_serve_command() {
        let args = vec!["serve".to_string()];
        let command = parse_args(&args);
        assert_eq!(command, CliCommand::Serve);
    }

    #[test]
    fn test_default_config() {
        let config = CliConfig::default();
        assert_eq!(config.server_address, "127.0.0.1:4222");
        assert_eq!(config.timeout_ms, 30000);
    }

    #[test]
    fn test_parse_help_and_unknown() {
        assert_eq!(parse_args(&vec!["--help".to_string()]), CliCommand::Help);
        assert_eq!(
            parse_args(&vec!["unknown-cmd".to_string(), "arg".to_string()]),
            CliCommand::Unknown {
                argv: vec!["unknown-cmd".to_string(), "arg".to_string()]
            }
        );
    }

    #[test]
    fn test_describe_command() {
        assert_eq!(
            describe_command(&CliCommand::Query {
                query: "rust".to_string()
            }),
            "query: rust"
        );
        assert_eq!(describe_command(&CliCommand::Status), "status");
    }
}
