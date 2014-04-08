(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [taoensso.timbre :as logging]
   [pallet.action :refer [implementation]]
   [pallet.actions.direct :refer [direct-script]]
   [pallet.actions.direct.execute :refer [result-with-error-map]]
   [pallet.core.context :refer [with-domain]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.core.script-state :as script-state :refer [update-node-state]]
   [pallet.exception :refer [domain-info]]
   [pallet.ssh.execute :as ssh]
   [pallet.local.execute :as local]
   [pallet.node :as node :refer [primary-ip]]
   [pallet.transport :as transport]
   [pallet.user :refer [user?]]))

(defn execute-ssh
  [transport node action value]
  (with-domain :ssh
    (ssh/ssh-script-on-target
     transport node (-> action :options :user) action value)))

(defn execute-local
  [node action value]
  (with-domain :local
    (local/script-on-origin (:user action) action value)))

(deftype SshActionExecutor [transport state]
  ActionExecutor
  (execute [executor node action]
    {:pre [node (map? action)]}
    (let [[metadata value] (direct-script
                            action
                            (script-state/node-state @state (node/id node)))]
      (logging/debugf "metadata %s" (pr-str metadata))
      (logging/debugf "value %s" (pr-str value))
      (logging/debugf "options %s" (pr-str (:options action)))
      (case (:action-type metadata :script)
        :script (let [{:keys [exit err out] :as result}
                      (if (= :target (:location metadata :target))
                        (execute-ssh transport node action value)
                        (execute-local node action value))
                      result (merge result (select-keys metadata [:summary]))]
                  (when out
                    (swap! state update-node-state (node/id node) out))
                  (when (and exit (pos? exit)
                             (:error-on-non-zero-exit (:options action) true))
                    (let [result (result-with-error-map
                                  (primary-ip node) "Error executing script"
                                  result)]
                      (throw
                       (domain-info
                        (str "Action failed: " err)
                        {:result result}))))
                  result)

        :transfer/from-local {:return-value ((:f value) node)}
        :transfer/to-local (ssh/ssh-to-local
                            transport node (:user action) value))))

  ActionExecutorState
  (node-state [executor node]
    (script-state/node-state @state (node/id node))))

(defn ssh-executor
  []
  (SshActionExecutor. (transport/factory :ssh {}) (atom {})))
