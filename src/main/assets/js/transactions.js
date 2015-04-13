(function () {
   'use strict';
}());

angular.module('slicebox.transactions', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/transactions', {
    templateUrl: '/assets/partials/transactions.html',
    controller: 'TransactionsCtrl'
  });
})

.controller('TransactionsCtrl', function($scope) {
})

.controller('InboxCtrl', function($scope, $http, $interval) {

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.inboxTable)) {
            $scope.callbacks.inboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadInboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/inbox');
    };
})

.controller('OutboxCtrl', function($scope, $http, $q, $interval, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteEntities
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outboxTable)) {
            $scope.callbacks.outboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };

    $scope.convertOutboxPageData = function(outboxEntries) {
        var outboxDataCollector = {};
        var outboxTransactionData;
        var imagesLeft;
        var pageData = [];

        var id = 1;
        angular.forEach(outboxEntries, function(outboxEntry) {
            outboxTransactionData = outboxDataCollector[outboxEntry.transactionId];
            if (angular.isUndefined(outboxTransactionData)) {
                outboxTransactionData =
                    {
                        id: id, 
                        remoteBoxName: outboxEntry.remoteBoxName,
                        totalImageCount: outboxEntry.totalImageCount,
                        failed: outboxEntry.failed,
                        outboxEntryIds: [],
                        imagesLeft: 0
                    };

                outboxDataCollector[outboxEntry.transactionId] = outboxTransactionData;
                id = id + 1;
            }

            outboxTransactionData.outboxEntryIds.push(outboxEntry.id);
            outboxTransactionData.imagesLeft = outboxTransactionData.imagesLeft + 1;
        });

        angular.forEach(outboxDataCollector, function(outboxTransactionData) {
            pageData.push(outboxTransactionData);
        });

        return pageData;
    };

    // private functions
    function confirmDeleteEntities(entities) {
        var deleteConfirmationText = 'Permanently delete ' + entities.length + ' transaction(s)?';

        return openConfirmationDeleteModal('Delete transaction(s)', deleteConfirmationText, function() {
            return deleteEntities(entities);
        });
    }

    function deleteEntities(entities) {
        var deletePromises = [];
        var deletePromise;
        var deleteAllPromises;

        angular.forEach(entities, function(entity) {
            for (var i = 0; i < entity.outboxEntryIds.length; i++) {
                deletePromise = $http.delete('/api/outbox/' + entity.outboxEntryIds[i]);
                deletePromises.push(deletePromise);
            }
        });

        deleteAllPromises = $q.all(deletePromises);

        deleteAllPromises.then(function() {
            $scope.showInfoMessage(entities.length + " transaction(s) deleted");
        }, function(response) {
            $scope.showErrorMessage(response.data);
        });

        return deleteAllPromises;
    }

})

.controller('BoxLogCtrl', function($scope, $http, $interval) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/log/', 'log message(s)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.logTable)) {
            $scope.callbacks.logTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        return $http.get('/api/log?startindex=' + startIndex + '&count=' + count + '&subject=Box');
    };

});