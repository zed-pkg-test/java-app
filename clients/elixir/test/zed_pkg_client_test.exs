defmodule ZedPkgClientTest do
  use ExUnit.Case, async: true

  test "rejects credentials in the registry URL" do
    assert_raise ArgumentError, fn -> ZedPkgClient.new(registry_url: "https://user:pass@example.com") end
  end

  test "accepts an absolute HTTP URL" do
    assert %ZedPkgClient{} = ZedPkgClient.new(registry_url: "http://localhost:8080/api/")
  end
end
