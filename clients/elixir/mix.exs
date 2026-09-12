defmodule ZedPkgClient.MixProject do
  use Mix.Project

  def project do
    [
      app: :zed_pkg_client,
      version: "0.1.0",
      elixir: "~> 1.16",
      start_permanent: Mix.env() == :prod,
      deps: [{:jason, "~> 1.4"}],
      description: "Elixir client for the zed-pkg registry",
      package: [licenses: ["MIT"], links: %{"GitHub" => "https://github.com/zed-pkg/zed-clients"}]
    ]
  end

  def application do
    [extra_applications: [:logger, :inets, :ssl, :crypto]]
  end
end
