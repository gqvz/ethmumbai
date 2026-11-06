// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// Anchors each Atlas decision episode on 0G Chain: the sha256 of the canonical episode JSON and the 0G Storage root it was uploaded to.
/// Anyone can re-download the episode from 0G Storage, hash it, and compare against the Anchored event.
contract EpisodeAnchor {
    event Anchored(address indexed agent, uint256 indexed episodeId, bytes32 sha256Hash, bytes32 storageRoot, uint64 ts);
    mapping(address => uint256) public count;

    function anchor(uint256 episodeId, bytes32 sha256Hash, bytes32 storageRoot) external {
        count[msg.sender] += 1;
        emit Anchored(msg.sender, episodeId, sha256Hash, storageRoot, uint64(block.timestamp));
    }
}
